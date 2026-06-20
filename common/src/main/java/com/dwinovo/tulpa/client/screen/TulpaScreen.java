package com.dwinovo.tulpa.client.screen;

import com.dwinovo.tulpa.agent.llm.TulpaLlmClient;
import com.dwinovo.tulpa.agent.model.ModelRegistry;
import com.dwinovo.tulpa.agent.llm.ConvoState;
import com.dwinovo.tulpa.agent.provider.AssistantTurn;
import com.dwinovo.tulpa.agent.provider.LlmToolCall;
import com.dwinovo.tulpa.client.agent.AgentLoopRegistry;
import com.dwinovo.tulpa.client.agent.ClientDeaths;
import com.dwinovo.tulpa.client.agent.ClientTulpaLookup;
import com.dwinovo.tulpa.client.agent.EntityAgentLoop;
import com.dwinovo.tulpa.client.agent.TulpaRoster;
import com.dwinovo.tulpa.client.data.ClientTulpaInventory;
import com.dwinovo.tulpa.network.payload.RequestInventoryPayload;
import com.dwinovo.tulpa.platform.Services;
import com.dwinovo.tulpa.platform.services.ITulpaConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The owner-facing companion panel: one tabbed screen per Tulpa (Chat / Items /
 * Settings), opened from the roster or hotkey. Resolves the companion lazily by
 * UUID so it works at any distance.
 *
 * <h2>Chat tab</h2>
 * A scrollable transcript on the left + a live PLAN panel on the right. Tool calls
 * show a spinner while running and a green check once their result lands — the raw
 * tool-result JSON is NOT shown (it only flips the call to done), keeping the chat
 * readable. The plan is the companion's latest {@code todowrite}.
 */
public final class TulpaScreen extends Screen {

    private enum Tab { CHAT, ITEMS, SETTINGS }

    // ---- layout ----
    private static final int PANEL_W = 380;
    private static final int PANEL_H = 232;
    // Left companion rail (folded-in roster): one avatar per Tulpa, click to switch, + to summon.
    private static final int RAIL_W = 46;        // left rail column width (baked into the workspace sprite)
    private static final int RAIL_AV = 26;       // avatar tile size
    private static final int RAIL_SLOT = 32;     // vertical pitch per avatar
    private static final int RAIL_TOP = 12;      // top margin before the first avatar (clears the active crown)
    private static final int RAIL_BOT_GAP = 6;   // gap kept above the pinned "+" tile
    private static final int HEADER_H = 22;
    private static final int INPUT_H = 18;
    /** Text fields are inset inside their parchment frame: the EditBox is shrunk by this much
     *  (so vanilla's top-left unbordered text lands padded + centred) and the FIELD_SPRITE is
     *  inflated back out to the full frame. */
    private static final int FIELD_INSET_X = 5;
    private static final int FIELD_INSET_Y = 4;
    private static final int PAD = 8;
    private static final int LINE_H = 10;
    private static final int PLAN_W = 122;
    private static final int MAX_PROMPT = 1024;
    private static final int TOOL_ARG_CHARS = 44;

    // ---- palette (BlockFrame "Cottage" theme — single theme for now, see UiTheme) ----
    private static final UiTheme TH = UiTheme.WARM;
    private static final int BORDER = TH.border();
    private static final int ACCENT = TH.cta();
    private static final int TXT = TH.text();
    private static final int TXT_MUTED = TH.textDim();
    private static final int TXT_FAINT = 0xFF8C7C62;
    private static final int ON_BAND = TH.onBand();
    private static final int CTA = TH.cta();
    private static final int ON_CTA = TH.onCta();
    private static final int FIELD = TH.field();
    private static final int YOU = TH.reply();          // user messages — teal
    private static final int AI = 0xFF35562F;            // assistant replies — deep moss green (the "point")
    private static final int TOOL = TH.textDim();        // folded tool-call rows — muted, secondary
    private static final int OK = TH.ok();
    private static final int RUN = TH.run();
    private static final int FAIL = TH.fail();
    private static net.minecraft.resources.ResourceLocation railSpr(String n) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.dwinovo.tulpa.Constants.MOD_ID, n);
    }
    /** rail + panel composited into ONE sprite (continuous header, no gap; panel's left border = divider). */
    private static final net.minecraft.resources.ResourceLocation WORKSPACE_SPRITE = railSpr("workspace");
    private static final net.minecraft.resources.ResourceLocation AVATAR_FRAME = railSpr("avatar_frame");
    private static final net.minecraft.resources.ResourceLocation AVATAR_FRAME_ACTIVE = railSpr("avatar_frame_active");
    private static final net.minecraft.resources.ResourceLocation SUMMON_SPRITE = railSpr("summon");
    private static final net.minecraft.resources.ResourceLocation SUMMON_ACTIVE = railSpr("summon_active");
    /** API-key reveal toggle icons: open eye = "click to show", slashed eye = "click to hide". */
    private static final net.minecraft.resources.ResourceLocation EYE = railSpr("eye");
    private static final net.minecraft.resources.ResourceLocation EYE_OFF = railSpr("eye_off");
    private static final net.minecraft.resources.ResourceLocation CHEVRON_UP = railSpr("chevron_up");
    private static final net.minecraft.resources.ResourceLocation CHEVRON_DOWN = railSpr("chevron_down");

    private static final String[] SPIN = {"|", "/", "-", "\\"};
    /** Armor column on the Items tab (top → bottom); offhand is drawn separately below it. */
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private UUID uuid;       // active companion (mutable — the rail switches it in place)
    private String name;
    private Tab tab = Tab.CHAT;

    private EditBox input;
    private SimpleButton sendButton;
    private SimpleButton stopButton;
    private SimpleButton compactButton;
    private String savedInput = "";

    // "+" summon flow: a transient name field shown over the panel
    private boolean summoning;
    private EditBox summonInput;
    private UUID dismissPending;   // non-null = showing the "delete companion?" confirm bar for this uuid

    // settings tab widgets
    private ProviderDropdown providerDropdown;
    private Dropdown modelDropdown;          // null when in custom-model mode
    private boolean customModel;             // model is a free-text custom id (not a registry preset)
    private static final String CUSTOM_MODEL = "__custom__";
    // unsaved working state — settings widgets are (re)built from these, NOT from config, so a rebuild
    // (provider change / custom toggle) doesn't revert what you just picked or typed.
    private String wProvider = "", wApiKey = "", wModel = "", wBaseUrl = "", wProxy = "", wSiteName = "";
    private boolean addingSite;              // "+ 添加站点" mode: name + base URL + model → writes a site
    private EditBox proxyInput;
    private EditBox siteNameInput;
    private EditBox apiKeyInput;
    private EditBox modelInput;
    private EditBox baseUrlInput;
    private long savedFlashUntil;
    private long warnUntil;        // transient "no API key" hint on the chat tab

    // Widgets are registered for EVENTS only (addWidget) and rendered MANUALLY at the end of the
    // frame, so they sit ON TOP of the panel background instead of being painted over by it (the
    // "dim fields" bug — the panel fill ran after the auto-rendered widgets).
    private final List<AbstractWidget> overlay = new ArrayList<>();
    /** API key is masked by default; the eye button toggles it. */
    private boolean showKey;

    // geometry resolved in init()
    private int left, top, railX;
    private final int[] tabX = new int[3];   // left x of each tab label, for click hit-testing
    private final int[] tabW = new int[3];

    // chat transcript scroll
    private int scroll;            // px scrolled down from the top of the content
    private boolean pinBottom = true;
    private int lastMaxScroll;
    private int railScroll;        // index of the first visible rail avatar (wheel-scroll when many companions)
    /** Completed tool-call groups the user clicked open (keyed by the group's first call id). */
    private final Set<String> expandedGroups = new HashSet<>();

    /** Re-request the backpack every ~1 s while the Items tab is open. */
    private static final int INV_REFRESH_TICKS = 20;
    private int tickCounter;

    private TulpaScreen(UUID uuid, String name) {
        super(Component.literal(name == null ? "Tulpa" : "Tulpa - " + name));
        this.uuid = uuid;
        this.name = name;
    }

    /** Open the panel focused on a specific companion. */
    public static void open(UUID uuid, String name) {
        Minecraft.getInstance().setScreen(new TulpaScreen(uuid, name));
    }

    /** Hotkey entry: open the workspace on the first companion (or an empty panel to summon from). */
    public static void openWorkspace() {
        var entries = TulpaRoster.instance().entries();
        if (entries.isEmpty()) { Minecraft.getInstance().setScreen(new TulpaScreen(null, null)); return; }
        TulpaRoster.Entry first = entries.get(0);
        Minecraft.getInstance().setScreen(new TulpaScreen(first.uuid(), first.name()));
    }

    /** Switch the panel to another companion in place (left-rail click) — no reopen. */
    private void switchTo(UUID u, String n) {
        if (java.util.Objects.equals(u, uuid)) return;
        input = null; savedInput = "";          // don't carry typed text across companions
        uuid = u; name = n;
        scroll = 0; pinBottom = true; expandedGroups.clear();
        rebuild();
        if (tab == Tab.ITEMS && u != null) requestInventory();
    }

    private EntityAgentLoop loop() {
        return AgentLoopRegistry.getOrCreate(uuid);
    }

    @Override
    protected void init() {
        int composite = RAIL_W + PANEL_W;        // rail flush against the panel — one merged sprite
        this.railX = (this.width - composite) / 2;
        this.left = railX + RAIL_W;
        this.top = (this.height - PANEL_H) / 2;
        layoutTabs();
        rebuild();
    }

    private void layoutTabs() {
        String[] labels = {"Chat", "Items", "Settings"};
        int x = left + PANEL_W - PAD;
        for (int i = labels.length - 1; i >= 0; i--) {
            int w = font.width(labels[i]) + 10;
            x -= w;
            tabX[i] = x;
            tabW[i] = w;
            x -= 4;
        }
    }

    /** Rebuild the widgets for the active tab. */
    private void rebuild() {
        if (input != null) savedInput = input.getValue();
        clearWidgets();
        overlay.clear();
        input = null;
        sendButton = stopButton = compactButton = null;
        apiKeyInput = modelInput = baseUrlInput = proxyInput = siteNameInput = null;
        modelDropdown = null;
        summonInput = null;
        if (summoning) { buildSummonField(); return; }
        if (dismissPending != null) { buildDismissConfirm(); return; }
        switch (tab) {
            case CHAT -> { if (uuid != null) buildChatWidgets(); }
            case SETTINGS -> buildSettingsWidgets();
            case ITEMS -> { /* no widgets */ }
        }
    }

    private void buildSummonField() {
        int y = top + HEADER_H + 24;
        summonInput = new FlatEditBox(font, left + PAD + FIELD_INSET_X, y + FIELD_INSET_Y,
                PANEL_W - PAD * 2 - FIELD_INSET_X * 2, 18 - FIELD_INSET_Y * 2, Component.literal(""));
        summonInput.setMaxLength(com.dwinovo.tulpa.network.payload.SummonRequestPayload.MAX_NAME);
        summonInput.setBordered(false);
        summonInput.setTextColor(TXT);
        summonInput.setHint(Component.literal("New companion name…"));
        add(summonInput);
        setInitialFocus(summonInput);
    }

    /** Two buttons for the "delete companion?" confirm bar — Cancel and the destructive Delete. */
    private void buildDismissConfirm() {
        UUID target = dismissPending;
        int bw = 64, gap = 8, totalW = bw * 2 + gap;
        int bx = left + (PANEL_W - totalW) / 2;
        int by = top + HEADER_H + 52;
        add(new SimpleButton(bx, by, bw, 18, Component.literal("取消"),
                b -> { dismissPending = null; rebuild(); }));
        add(new SimpleButton(bx + bw + gap, by, bw, 18, Component.literal("删除"), b -> {
            Services.NETWORK.sendToServer(
                    new com.dwinovo.tulpa.network.payload.DismissRequestPayload(target));
            dismissPending = null;
            if (target.equals(uuid)) {                       // active one is leaving — jump to another / empty
                TulpaRoster.Entry next = firstOther(target);
                if (next != null) { switchTo(next.uuid(), next.name()); return; }
                uuid = null; name = null;
            }
            rebuild();
        }));
    }

    /** First roster companion that isn't {@code exclude}, or null if none. */
    private TulpaRoster.Entry firstOther(UUID exclude) {
        for (TulpaRoster.Entry e : TulpaRoster.instance().entries()) {
            if (!e.uuid().equals(exclude)) return e;
        }
        return null;
    }

    private String nameFor(UUID u) {
        for (TulpaRoster.Entry e : TulpaRoster.instance().entries()) {
            if (e.uuid().equals(u)) return e.name();
        }
        return "?";
    }

    /** Register a widget for EVENTS only; it's rendered manually (on top of the panel) in {@link
     *  #render}. */
    private <T extends AbstractWidget> T add(T w) {
        addWidget(w);
        overlay.add(w);
        return w;
    }

    // Shadowless text — BlockFrame is flat, and a drop shadow on DARK text over a LIGHT ground makes
    // the glyph merge with its own shadow ("smudged"). This build's shadowless path ignores the colour
    // PARAM, so we bake the colour into the text's Style instead.
    private void txt(GuiGraphics g, Component c, int x, int y, int color) {
        g.drawString(font, c.copy().withStyle(s -> s.withColor(
                net.minecraft.network.chat.TextColor.fromRgb(color & 0xFFFFFF))), x, y, -1, false);
    }

    /** The FormattedCharSequence must already carry its colour (see {@link #colored}). */
    private void txt(GuiGraphics g, FormattedCharSequence c, int x, int y, int color) {
        g.drawString(font, c, x, y, -1, false);
    }

    /** A coloured text Component (colour in the Style, so shadowless rendering keeps it). */
    private static Component colored(String s, int color) {
        return Component.literal(s).withStyle(st -> st.withColor(
                net.minecraft.network.chat.TextColor.fromRgb(color & 0xFFFFFF)));
    }


    private void buildChatWidgets() {
        int inputY = top + PANEL_H - INPUT_H - PAD;
        int compactW = 26;
        int sendW = 42;
        int stopW = 22;
        int inX = left + PAD + compactW + 4;
        int inW = PANEL_W - PAD * 2 - compactW - sendW - stopW - 12;

        compactButton = add(new SimpleButton(left + PAD, inputY, compactW, INPUT_H,
                Component.literal("⤬"), b -> loop().requestCompact()));
        compactButton.active = loop().canCompact();

        input = new FlatEditBox(font, inX + FIELD_INSET_X, inputY + FIELD_INSET_Y,
                inW - FIELD_INSET_X * 2, INPUT_H - FIELD_INSET_Y * 2, Component.literal("tulpa.chat.input"));
        input.setMaxLength(MAX_PROMPT);
        input.setBordered(false);
        input.setTextColor(TXT);
        // FlatEditBox draws the hint shadowless and UNDER the caret (same widget pass), so use it
        // directly — no separate screen-side placeholder that would paint over the blinking caret.
        // Faint colour is baked into the Component's Style.
        input.setHint(Nb.colored("Talk to " + (name == null ? "" : name) + "…", TXT_FAINT));
        if (!savedInput.isEmpty()) { input.setValue(savedInput); savedInput = ""; }
        add(input);
        setInitialFocus(input);

        sendButton = add(new SimpleButton(inX + inW + 4, inputY, sendW, INPUT_H,
                Component.literal("Send"), b -> onSend()));

        stopButton = add(new SimpleButton(inX + inW + 4 + sendW + 4, inputY, stopW, INPUT_H,
                Component.literal("■"), b -> loop().abort()));
        stopButton.active = loop().canInterrupt();
    }

    private void selectTab(Tab t) {
        if (t == tab) return;
        tab = t;
        scroll = 0;
        pinBottom = true;
        if (t == Tab.ITEMS) requestInventory();
        if (t == Tab.SETTINGS) initModelMode();
        rebuild();
    }

    /** Decide once (on entering Settings) whether the model field starts as a preset dropdown or a
     *  custom text box: custom-provider or a configured model that isn't a known preset → custom. */
    private void initModelMode() {
        ITulpaConfig cfg = Services.CONFIG;
        wProvider = cfg.getProvider() == null ? "openai" : cfg.getProvider();
        wApiKey = cfg.getApiKey() == null ? "" : cfg.getApiKey();
        wModel = cfg.getModel() == null ? "" : cfg.getModel();
        wBaseUrl = cfg.getBaseUrl() == null ? "" : cfg.getBaseUrl();
        wProxy = cfg.getProxy() == null ? "" : cfg.getProxy();
        addingSite = false;
        ModelRegistry.Provider mp = ModelRegistry.provider(LlmProviders.normalize(wProvider));
        boolean known = mp != null && mp.models().stream().anyMatch(m -> m.id().equals(wModel));
        customModel = (mp != null && mp.custom()) || (!wModel.isBlank() && !known);
    }

    /** Snapshot the API-key + base-URL fields before a settings rebuild so the edits survive it. */
    private void preserveKeyUrl() {
        if (apiKeyInput != null) wApiKey = apiKeyInput.getValue();
        if (baseUrlInput != null) wBaseUrl = baseUrlInput.getValue();
        if (proxyInput != null) wProxy = proxyInput.getValue();
    }

    // ---- settings tab ----

    private static final int SET_SP = 33;     // settings row pitch (5 rows + Save must fit)

    private void buildSettingsWidgets() {
        int x = left + PAD, w = PANEL_W - PAD * 2;
        int y0 = top + HEADER_H + 8;

        if (addingSite) {
            // row0: site name + cancel
            siteNameInput = field(x, y0 + 11, w - 20, 64, wSiteName);
            add(new SimpleButton(x + w - 18, y0 + 11, 18, 18, Component.literal("✕"),
                    b -> { addingSite = false; rebuild(); }));
            buildApiKeyRow(x, y0 + SET_SP + 11, w);
            modelInput = field(x, y0 + 2 * SET_SP + 11, w, 128, wModel);
            baseUrlInput = field(x, y0 + 3 * SET_SP + 11, w, 256, wBaseUrl);
        } else {
            providerDropdown = new ProviderDropdown(wProvider, true);   // live + "+ 添加站点"
            providerDropdown.setBounds(x, y0 + 11, w, 18);
            buildApiKeyRow(x, y0 + SET_SP + 11, w);
            buildModelRow(x, y0 + 2 * SET_SP + 11, w);
            baseUrlInput = field(x, y0 + 3 * SET_SP + 11, w, 256, wBaseUrl);
            proxyInput = field(x, y0 + 4 * SET_SP + 11, w, 128, wProxy);
        }

        add(new SimpleButton(left + PANEL_W - PAD - 64, top + PANEL_H - PAD - 18,
                64, 18, Component.literal("Save"), b -> onSaveSettings()));
    }

    private void buildApiKeyRow(int x, int y, int w) {
        int eyeW = 22;
        apiKeyInput = field(x, y, w - eyeW - 2, 512, wApiKey);
        apiKeyInput.setFormatter((text, idx) -> showKey
                ? FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY)
                : FormattedCharSequence.forward("•".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
        // Eye icon instead of a 见/隐 glyph: open eye when masked (click to show), slashed when shown.
        add(new SimpleButton(x + w - eyeW, y, eyeW, 18, Component.empty(),
                b -> { showKey = !showKey; ((SimpleButton) b).icon(showKey ? EYE_OFF : EYE); })
                .icon(showKey ? EYE_OFF : EYE));
    }

    /** Model row: a preset dropdown for the provider's known models, or a free-text box (custom mode)
     *  with a "▾" toggle back to presets. A custom provider (openai-compatible) is always free-text. */
    private void buildModelRow(int x, int y, int w) {
        ModelRegistry.Provider mp = ModelRegistry.provider(LlmProviders.normalize(providerDropdown.selectedId()));
        boolean providerCustom = mp != null && mp.custom();
        if (customModel || providerCustom) {
            customModel = true;
            modelDropdown = null;
            modelInput = field(x, y, providerCustom ? w : w - 20, 128, wModel);
            if (!providerCustom) {     // a way back to the preset list (custom providers have none)
                add(new SimpleButton(x + w - 18, y, 18, 18, Component.literal("▾"),
                        b -> { preserveKeyUrl(); customModel = false; rebuild(); }));
            }
        } else {
            modelInput = null;
            boolean known = mp != null && mp.models().stream().anyMatch(m -> m.id().equals(wModel));
            String sel = known ? wModel
                    : (mp != null && !mp.models().isEmpty() ? mp.models().get(0).id() : CUSTOM_MODEL);
            modelDropdown = new Dropdown(modelItems(mp), sel);
            modelDropdown.setBounds(x, y, w, 18);
        }
    }

    private List<Dropdown.Item> modelItems(ModelRegistry.Provider mp) {
        List<Dropdown.Item> items = new ArrayList<>();
        if (mp != null) for (ModelRegistry.Model m : mp.models()) items.add(new Dropdown.Item(m.id(), m.id()));
        items.add(new Dropdown.Item(CUSTOM_MODEL, "自定义…"));
        return items;
    }

    /** Shadowless placeholder for an empty, unfocused field — the EditBox's own hint renders with a shadow. */
    private void placeholder(GuiGraphics g, EditBox f, String text) {
        if (f != null && f.getValue().isEmpty() && !f.isFocused() && text != null && !text.isEmpty()) {
            txt(g, Component.literal(text), f.getX(), f.getY(), TXT_FAINT);
        }
    }

    private EditBox field(int x, int y, int w, int max, String value) {
        EditBox e = new FlatEditBox(font, x + FIELD_INSET_X, y + FIELD_INSET_Y,
                w - FIELD_INSET_X * 2, 18 - FIELD_INSET_Y * 2, Component.literal(""));
        e.setMaxLength(max);
        e.setValue(value == null ? "" : value);
        e.setBordered(false);
        e.setTextColor(TXT);
        add(e);
        return e;
    }

    private void onSaveSettings() {
        ITulpaConfig cfg = Services.CONFIG;
        if (addingSite) {                          // create a new user site, then select it
            String name = siteNameInput.getValue().trim();
            String url = baseUrlInput.getValue().trim();
            String mdl = modelInput.getValue().trim();
            if (name.isEmpty() || url.isEmpty()) { warnUntil = System.currentTimeMillis() + 4000; return; }
            String id = ModelRegistry.addCustomSite(name, url, mdl);
            if (id == null) { warnUntil = System.currentTimeMillis() + 4000; return; }
            cfg.setProvider(id);
            cfg.setModel(mdl);
            cfg.setApiKey(apiKeyInput.getValue());
            cfg.setBaseUrl("");                    // site carries the URL now
            cfg.setProxy(wProxy);
            cfg.save();
            TulpaLlmClient.reset();
            addingSite = false;
            wProvider = id; wModel = mdl; wBaseUrl = ""; customModel = false;
            rebuild();
            savedFlashUntil = System.currentTimeMillis() + 1500;
            return;
        }
        cfg.setProvider(providerDropdown.selectedId());
        cfg.setApiKey(apiKeyInput.getValue());
        String model = customModel
                ? (modelInput != null ? modelInput.getValue().trim() : "")
                : (modelDropdown != null && !CUSTOM_MODEL.equals(modelDropdown.selectedId())
                        ? modelDropdown.selectedId() : "");
        cfg.setModel(model);
        cfg.setBaseUrl(baseUrlInput.getValue());
        cfg.setProxy(proxyInput == null ? wProxy : proxyInput.getValue());
        cfg.save();
        TulpaLlmClient.reset();
        savedFlashUntil = System.currentTimeMillis() + 1500;
    }

    private void renderSettings(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + PAD;
        int y0 = top + HEADER_H + 8;
        if (addingSite) {
            txt(g, Component.literal("Site name"), x, y0, TXT_MUTED);
            txt(g, Component.literal("API Key"), x, y0 + SET_SP, TXT_MUTED);
            txt(g, Component.literal("Model"), x, y0 + 2 * SET_SP, TXT_MUTED);
            txt(g, Component.literal("Base URL"), x, y0 + 3 * SET_SP, TXT_MUTED);
        } else {
            txt(g, Component.literal("Provider"), x, y0, TXT_MUTED);
            txt(g, Component.literal("API Key"), x, y0 + SET_SP, TXT_MUTED);
            txt(g, Component.literal("Model"), x, y0 + 2 * SET_SP, TXT_MUTED);
            txt(g, Component.literal("Base URL"), x, y0 + 3 * SET_SP, TXT_MUTED);
            txt(g, Component.literal("Proxy"), x, y0 + 4 * SET_SP, TXT_MUTED);
        }
        if (savedFlashUntil > System.currentTimeMillis()) {
            txt(g, Component.literal("✔ saved"), x, top + PANEL_H - PAD - 14, OK);
        }
        // the dropdowns themselves render in render, AFTER the widgets (open list on top)
    }

    @Override
    public void tick() {
        if (tab == Tab.ITEMS && ++tickCounter % INV_REFRESH_TICKS == 0) {
            requestInventory();
        }
    }

    private void requestInventory() {
        if (Minecraft.getInstance().getConnection() != null) {
            Services.NETWORK.sendToServer(new RequestInventoryPayload(uuid));
        }
    }

    private void onSend() {
        if (input == null) return;
        String text = input.getValue() == null ? "" : input.getValue().trim();
        if (text.isEmpty()) return;
        if (!TulpaLlmClient.isConfigured()) {
            com.dwinovo.tulpa.Constants.LOG.warn("[tulpa-chat] no apiKey; open Settings.");
            warnUntil = System.currentTimeMillis() + 4000;   // visible hint instead of a silent no-op
            return;
        }
        loop().submitPrompt(text);
        input.setValue("");
        pinBottom = true;
    }

    // ---- input ----

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int k = keyCode;
        if (dismissPending != null) {
            if (k == 256) { dismissPending = null; rebuild(); return true; }   // Esc cancels the confirm
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (summoning) {
            if (k == 257 || k == 335) { doSummon(); return true; }    // Enter
            if (k == 256) { summoning = false; rebuild(); return true; } // Esc cancels (doesn't close panel)
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if ((k == 257 || k == 335) && input != null && input.isFocused()) {
            onSend();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void doSummon() {
        String n = summonInput == null ? "" : summonInput.getValue().trim();
        if (n.isEmpty()) return;
        Services.NETWORK.sendToServer(new com.dwinovo.tulpa.network.payload.SummonRequestPayload(n));
        summoning = false;
        rebuild();   // the new companion arrives via CompanionListPayload — click its avatar to open
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (dismissPending != null) {
            return super.mouseClicked(mouseX, mouseY, button);   // modal confirm — let its Cancel/Delete buttons handle it
        }
        if (button == 0) {
            UUID close = railCloseAt((int) mouseX, (int) mouseY);
            if (close != null) { dismissPending = close; rebuild(); return true; }   // ✕ → confirm bar
            if (railPlusAt((int) mouseX, (int) mouseY)) {   // + → start the summon name prompt
                summoning = !summoning;
                rebuild();
                return true;
            }
            int rail = railIndexAt((int) mouseX, (int) mouseY);
            if (rail >= 0) {
                List<TulpaRoster.Entry> entries = TulpaRoster.instance().entries();
                if (rail < entries.size()) {
                    boolean wasSummoning = summoning;
                    summoning = false;
                    TulpaRoster.Entry e = entries.get(rail);
                    if (e.uuid().equals(uuid)) { if (wasSummoning) rebuild(); }   // already active — just exit summon
                    else switchTo(e.uuid(), e.name());
                }
                return true;
            }
            if (tab == Tab.SETTINGS && providerDropdown != null) {
                String before = providerDropdown.selectedId();
                if (providerDropdown.mouseClicked(mouseX, mouseY)) {
                    if (modelDropdown != null) modelDropdown.close();
                    String sel = providerDropdown.selectedId();
                    if (ProviderDropdown.ADD_SITE.equals(sel)) {            // "+ 添加站点" → add-site editor
                        preserveKeyUrl();
                        addingSite = true; wSiteName = ""; wBaseUrl = ""; wModel = "";
                        rebuild();
                    } else if (!sel.equals(before)) {                      // provider changed → reset model
                        preserveKeyUrl();
                        wProvider = sel;
                        ModelRegistry.Provider mp = ModelRegistry.provider(LlmProviders.normalize(wProvider));
                        customModel = mp != null && mp.custom();
                        wModel = (mp != null && !mp.models().isEmpty()) ? mp.models().get(0).id() : "";
                        rebuild();
                    }
                    return true;
                }
            }
            if (tab == Tab.SETTINGS && modelDropdown != null
                    && modelDropdown.mouseClicked(mouseX, mouseY)) {
                providerDropdown.close();
                if (CUSTOM_MODEL.equals(modelDropdown.selectedId())) {       // "自定义…" → free-text box
                    preserveKeyUrl();
                    customModel = true;
                    wModel = "";
                    rebuild();
                }
                return true;
            }
            int my = (int) mouseY;
            if (my >= top && my < top + HEADER_H) {
                for (int i = 0; i < 3; i++) {
                    if (mouseX >= tabX[i] && mouseX < tabX[i] + tabW[i]) {
                        selectTab(Tab.values()[i]);
                        return true;
                    }
                }
            }
            if (tab == Tab.CHAT && toggleFoldAt((int) mouseX, my)) return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** If a chat fold-toggle row sits under (mx,my), flip its expanded state. Mirrors renderChat geometry. */
    private boolean toggleFoldAt(int mx, int my) {
        int bodyY = top + HEADER_H + 4;
        int bodyBottom = top + PANEL_H - INPUT_H - PAD - 6;
        int transX = left + PAD;
        int transW = PANEL_W - PAD * 2 - PLAN_W - 8;
        if (mx < transX || mx >= transX + transW || my < bodyY || my >= bodyBottom) return false;
        List<Row> rows = buildRows(transW);
        int idx = (my - (bodyY - scroll)) / LINE_H;
        if (idx < 0 || idx >= rows.size()) return false;
        String key = rows.get(idx).foldKey();
        if (key == null) return false;
        if (!expandedGroups.add(key)) expandedGroups.remove(key);   // toggle open/closed
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        // Wheel over the left rail column scrolls the roster (works on any tab).
        if (sy != 0 && mx >= railX && mx < railX + RAIL_W && maxRailScroll() > 0) {
            railScroll = Math.clamp((long) (railScroll - sy), 0, maxRailScroll());
            return true;
        }
        if (tab == Tab.CHAT && sy != 0) {
            scroll = Math.clamp((long) (scroll - sy * LINE_H * 3), 0, lastMaxScroll);
            pinBottom = scroll >= lastMaxScroll;
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    // ---- render ----

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);

        // ONE merged Cottage sprite: left rail column + panel, continuous header, no gap.
        g.blitSprite(
                WORKSPACE_SPRITE, railX, top, RAIL_W + PANEL_W, PANEL_H);
        renderRail(g, mouseX, mouseY);   // avatars + status + summon tile on the rail column

        txt(g, Component.literal(name == null ? "Tulpa" : name), left + PAD, top + 7, ON_BAND);
        if (uuid != null && ClientDeaths.isDead(uuid)) {        // active companion dead — respawn countdown
            long rem = ClientDeaths.remainingMs(uuid);
            txt(g, Component.literal("· 复活中 " + (int) Math.ceil(rem / 1000.0) + "s"),
                    left + PAD + font.width(name == null ? "Tulpa" : name) + 6, top + 7, ON_BAND);
        }
        renderTabs(g, mouseX, mouseY);

        if (dismissPending != null) {
            txt(g, Component.literal("删除同伴 \"" + nameFor(dismissPending) + "\"？"),
                    left + PAD, top + HEADER_H + 12, TXT);
            txt(g, Component.literal("永久删除 · 背包会掉落在原地 · 无法撤销"),
                    left + PAD, top + HEADER_H + 30, FAIL);
        } else if (summoning) {
            txt(g, Component.literal("Summon a companion"), left + PAD, top + HEADER_H + 8, TXT);
            txt(g, Component.literal("type a name · Enter to confirm · Esc to cancel"),
                    left + PAD, top + HEADER_H + 48, TXT_FAINT);
        } else {
            if (uuid != null) {
                if (compactButton != null) compactButton.active = loop().canCompact();
                if (stopButton != null) stopButton.active = loop().canInterrupt();
            }
            switch (tab) {
                case SETTINGS -> renderSettings(g, mouseX, mouseY);   // global — works with no companion
                case CHAT -> { if (uuid != null) renderChat(g); else emptyHint(g); }
                case ITEMS -> { if (uuid != null) renderItems(g, mouseX, mouseY); else emptyHint(g); }
            }
            if (tab == Tab.CHAT && warnUntil > System.currentTimeMillis()) {   // no-API-key hint above the input
                txt(g, Component.literal("⚠ No API key — open Settings to add one"),
                        left + PAD, top + PANEL_H - INPUT_H - PAD - 11, FAIL);
            }
        }

        // Widgets render LAST, on top of the panel background (fixes the "dim fields" — the panel fill
        // used to paint over the auto-rendered widgets). Text fields are borderless EditBoxes, so draw
        // a parchment field background + border behind each before it renders its text.
        for (AbstractWidget w : overlay) {
            if (w instanceof EditBox eb) {                          // parchment frame, inflated past the inset text
                g.blitSprite(
                        FIELD_SPRITE, eb.getX() - FIELD_INSET_X, eb.getY() - FIELD_INSET_Y,
                        eb.getWidth() + FIELD_INSET_X * 2, eb.getHeight() + FIELD_INSET_Y * 2);
            }
        }
        for (AbstractWidget w : overlay) {
            w.render(g, mouseX, mouseY, partial);
        }
        // Base URL / Proxy placeholders, drawn shadowless by us (the EditBox hint renders with a shadow).
        if (tab == Tab.SETTINGS) {
            String urlPh = addingSite ? "https://… (OpenAI-compatible)"
                    : LlmProviders.byId(providerDropdown.selectedId()).defaultBaseUrl();
            placeholder(g, baseUrlInput, urlPh);
            placeholder(g, proxyInput, "host:port (optional)");
            // Model + site-name placeholders, also shadowless (these EditBoxes are null outside
            // custom-model / add-site mode, and placeholder() no-ops on null/non-empty/focused).
            placeholder(g, modelInput, addingSite ? "model id"
                    : LlmProviders.byId(providerDropdown.selectedId()).defaultModel());
            if (addingSite) placeholder(g, siteNameInput, "e.g. My Proxy");
        }
        // (Chat-input placeholder is the FlatEditBox hint now — drawn shadowless and under the
        // caret in the widget pass, so it can't paint over the caret like a screen-side draw did.)
        // The provider dropdown's open list must sit above even the fields.
        if (tab == Tab.SETTINGS) {
            // render the non-open one first so the open list draws on top
            if (modelDropdown != null && providerDropdown != null && providerDropdown.isOpen()) {
                modelDropdown.render(g, font, mouseX, mouseY);
                providerDropdown.render(g, font, mouseX, mouseY);
            } else {
                if (providerDropdown != null) providerDropdown.render(g, font, mouseX, mouseY);
                if (modelDropdown != null) modelDropdown.render(g, font, mouseX, mouseY);
            }
        }
    }

    // ---- left companion rail ----

    /** The folded-in roster (on the merged sprite's rail column): one avatar head per companion below the
     *  green header, active one framed gold, a status dot each, + tile at the bottom. */
    private void renderRail(GuiGraphics g, int mouseX, int mouseY) {
        List<TulpaRoster.Entry> entries = TulpaRoster.instance().entries();
        int ax = railX + (RAIL_W - RAIL_AV) / 2;
        railScroll = Math.clamp(railScroll, 0, maxRailScroll());     // keep valid as the roster grows/shrinks
        int first = railScroll;
        int startY = railStartY();
        for (int i = first; i < entries.size(); i++) {
            int ay = startY + (i - first) * RAIL_SLOT;
            if (ay + RAIL_AV > railBottomEdge()) break;
            TulpaRoster.Entry e = entries.get(i);
            boolean active = e.uuid().equals(uuid);
            // textured socket behind the head (gold-bordered when active), then the avatar, then a status LED
            g.blitSprite(active ? AVATAR_FRAME_ACTIVE : AVATAR_FRAME, ax - 2, ay - 2, RAIL_AV + 4, RAIL_AV + 4);
            PlayerFaceRenderer.draw(g, skinFor(e.uuid()), ax, ay, RAIL_AV);
            if (ClientDeaths.isDead(e.uuid())) {                      // dead — dim veil + respawn countdown
                g.fill(ax, ay, ax + RAIL_AV, ay + RAIL_AV, 0xB0101010);
                long rem = ClientDeaths.remainingMs(e.uuid());
                if (rem >= 0) {
                    String c = String.valueOf((int) Math.ceil(rem / 1000.0));
                    txt(g, Component.literal(c), ax + (RAIL_AV - font.width(c)) / 2, ay + (RAIL_AV - 8) / 2, CTA);
                }
            } else {
                int d = ax + RAIL_AV - 6, e2 = ay + RAIL_AV - 6;     // status LED, bottom-right
                g.fill(d, e2, d + 5, e2 + 5, statusColor(e.uuid()));
                Nb.border(g, d, e2, 5, 5, 1, BORDER);
            }
            // hover → a small ✕ badge breaking OUT of the avatar's top-right corner (overhangs the frame).
            // Show it while the cursor is over the avatar OR the badge itself (the badge sticks out, so
            // moving onto it must not make it vanish).
            int bx = ax + RAIL_AV - 3, by = ay - 5;
            boolean overAvatar = mouseX >= ax && mouseX < ax + RAIL_AV && mouseY >= ay && mouseY < ay + RAIL_AV;
            boolean overBadge = mouseX >= bx && mouseX < bx + 9 && mouseY >= by && mouseY < by + 9;
            if (dismissPending == null && (overAvatar || overBadge)) {
                g.fill(bx, by, bx + 9, by + 9, FAIL);
                Nb.border(g, bx, by, 9, 9, 1, BORDER);
                txt(g, Component.literal("✕"), bx + 2, by + 1, ON_BAND);
            }
        }
        // "+" summon tile (baked "+" glyph), pinned to the rail bottom
        int py = top + PANEL_H - PAD - RAIL_AV;
        // scroll cues — gold chevrons when the roster overflows the rail in either direction
        int cx = ax + RAIL_AV / 2;
        if (railScroll > 0) chevron(g, cx, top + 1, true);
        if (railScroll < maxRailScroll()) chevron(g, cx, py - 9, false);
        g.blitSprite(summoning ? SUMMON_ACTIVE : SUMMON_SPRITE, ax, py, RAIL_AV, RAIL_AV);
    }

    /** Scroll-affordance chevron sprite (amber pixel-art triangle, up = more above / down = more below).
     *  Blitted at its native 11×6 so the pixels stay crisp (no scaling, no AA). */
    private void chevron(GuiGraphics g, int cx, int y, boolean up) {
        g.blitSprite(
                up ? CHEVRON_UP : CHEVRON_DOWN, cx - 5, y, 11, 6);
    }

    /** Bottom edge an avatar may reach (a gap above the pinned "+" tile). */
    private int railBottomEdge() {
        return top + PANEL_H - PAD - RAIL_AV - RAIL_BOT_GAP;
    }

    /** How many avatar slots fit in the rail above the pinned "+" tile. */
    private int railVisibleSlots() {
        int slots = 0;
        while (top + RAIL_TOP + slots * RAIL_SLOT + RAIL_AV <= railBottomEdge()) slots++;
        return Math.max(1, slots);
    }

    private int maxRailScroll() {
        return Math.max(0, TulpaRoster.instance().entries().size() - railVisibleSlots());
    }

    /** Y of the first (visible) avatar: centred vertically when the whole roster fits, top-aligned once
     *  it overflows and scrolls. Fixes the big bottom gap + the first avatar poking past the top edge. */
    private int railStartY() {
        int n = TulpaRoster.instance().entries().size();
        if (n > railVisibleSlots()) return top + RAIL_TOP;          // scrolling — top-align
        int blockH = Math.max(0, n - 1) * RAIL_SLOT + RAIL_AV;
        int span = railBottomEdge() - (top + RAIL_TOP);
        return top + RAIL_TOP + Math.max(0, (span - blockH) / 2);   // centre the block
    }

    /** The companion whose hover-✕ badge is under (mx,my), or null. Mirrors renderRail geometry. */
    private UUID railCloseAt(int mx, int my) {
        int ax = railX + (RAIL_W - RAIL_AV) / 2;
        List<TulpaRoster.Entry> entries = TulpaRoster.instance().entries();
        int first = Math.clamp(railScroll, 0, maxRailScroll());
        int startY = railStartY();
        for (int i = first; i < entries.size(); i++) {
            int ay = startY + (i - first) * RAIL_SLOT;
            if (ay + RAIL_AV > railBottomEdge()) break;
            int bx = ax + RAIL_AV - 3, by = ay - 5;   // overhanging top-right badge (matches renderRail)
            if (mx >= bx && mx < bx + 9 && my >= by && my < by + 9) return entries.get(i).uuid();
        }
        return null;
    }

    private boolean railPlusAt(int mx, int my) {
        int ax = railX + (RAIL_W - RAIL_AV) / 2;
        int py = top + PANEL_H - PAD - RAIL_AV;
        return mx >= ax && mx < ax + RAIL_AV && my >= py && my < py + RAIL_AV;
    }

    /** idle = green, working/compacting = amber, queued = gold; faint if no loop yet. */
    private int statusColor(UUID u) {
        return AgentLoopRegistry.get(u).map(loop -> {
            if (loop.isCompacting() || loop.isBusy()) return RUN;
            if (loop.hasQueuedPrompts()) return CTA;
            return OK;
        }).orElse(TXT_FAINT);
    }

    private static PlayerSkin skinFor(UUID u) {
        AbstractClientPlayer e = ClientTulpaLookup.resolve(u);
        return e != null ? e.getSkin() : DefaultPlayerSkin.get(u);
    }

    /** Roster index of the avatar under (mx,my), or -1. */
    private int railIndexAt(int mx, int my) {
        int ax = railX + (RAIL_W - RAIL_AV) / 2;
        if (mx < ax || mx >= ax + RAIL_AV) return -1;
        List<TulpaRoster.Entry> entries = TulpaRoster.instance().entries();
        int first = Math.clamp(railScroll, 0, maxRailScroll());
        int startY = railStartY();
        for (int i = first; i < entries.size(); i++) {
            int ay = startY + (i - first) * RAIL_SLOT;
            if (ay + RAIL_AV > railBottomEdge()) break;
            if (my >= ay && my < ay + RAIL_AV) return i;
        }
        return -1;
    }

    private void emptyHint(GuiGraphics g) {
        txt(g, Component.literal("No companions. Click + to summon one."),
                left + PAD, top + HEADER_H + 10, TXT_FAINT);
    }

    private void renderTabs(GuiGraphics g, int mouseX, int mouseY) {
        String[] labels = {"Chat", "Items", "Settings"};
        for (int i = 0; i < 3; i++) {
            boolean active = tab == Tab.values()[i];
            boolean hover = mouseX >= tabX[i] && mouseX < tabX[i] + tabW[i]
                    && mouseY >= top && mouseY < top + HEADER_H;
            int color = (active || hover) ? ON_BAND : (0x00FFFFFF & ON_BAND) | 0xA0000000;   // dim on band
            txt(g, Component.literal(labels[i]), tabX[i] + 5, top + 7, color);
            if (active) {                                                                     // gold CTA underline
                g.fill(tabX[i] + 3, top + HEADER_H - 4, tabX[i] + tabW[i] - 3, top + HEADER_H - 1, ACCENT);
            }
        }
    }

    private static final int ICON = 9;        // native vitals-icon size
    private static final int ICON_STEP = 9;   // touching = one chunky bar

    /** A row of segmented icons for a 0..max stat (2 units per icon): empty sockets first, then
     *  full / half overlaid. Used for hearts (HP) and drumsticks (hunger). */
    private void renderStatRow(GuiGraphics g, int x, int y, float value, float max,
                               net.minecraft.resources.ResourceLocation full,
                               net.minecraft.resources.ResourceLocation half,
                               net.minecraft.resources.ResourceLocation empty) {
        int units = Math.max(1, (int) Math.ceil(max / 2f));
        for (int i = 0; i < units; i++) {
            int ix = x + i * ICON_STEP;
            g.blitSprite(empty, ix, y, ICON, ICON);
            float v = value - i * 2f;
            if (v >= 2f)      g.blitSprite(full, ix, y, ICON, ICON);
            else if (v >= 1f) g.blitSprite(half, ix, y, ICON, ICON);
        }
    }

    /** Live mouse-following 3D portrait of the companion — the body IS a client player entity, so the
     *  vanilla player renderer draws it for free. Sits in a recessed socket (slot_alt stretched). */
    private void renderPortrait(GuiGraphics g, AbstractClientPlayer e,
                                int x, int y, int w, int h, int mouseX, int mouseY) {
        g.blitSprite(SLOT_ALT, x, y, w, h);
        if (e == null) return;
        int scale = (int) (h * 0.45f);
        net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsMouse(
                g, x + 2, y + 2, x + w - 2, y + h - 2, scale, 0.0625f,
                (float) mouseX, (float) mouseY, e);
    }

    private void slotBg(GuiGraphics g, net.minecraft.resources.ResourceLocation sprite, int x, int y) {
        g.blitSprite(sprite, x, y, 16, 16);
    }

    private void stackOn(GuiGraphics g, ItemStack st, int x, int y, int mouseX, int mouseY) {
        if (st == null || st.isEmpty()) return;
        g.renderItem(st, x, y);
        g.renderItemDecorations(font, st, x, y);
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            g.renderTooltip(font, st, mouseX, mouseY);
        }
    }

    /** One equipment/armor socket, read off the live client entity (equipment IS client-synced). */
    private void drawEquip(GuiGraphics g, AbstractClientPlayer e, EquipmentSlot slot,
                           int x, int y, int mouseX, int mouseY) {
        slotBg(g, SLOT_SPRITE, x, y);
        if (e != null) stackOn(g, e.getItemBySlot(slot), x, y, mouseX, mouseY);
    }

    // ---- chat transcript + plan ----

    private void renderChat(GuiGraphics g) {
        int bodyY = top + HEADER_H + 4;
        int bodyBottom = top + PANEL_H - INPUT_H - PAD - 6;
        int transX = left + PAD;
        int transW = PANEL_W - PAD * 2 - PLAN_W - 8;
        int viewH = bodyBottom - bodyY;

        // plan panel divider + content
        int planX = transX + transW + 8;
        g.fill(planX - 4, bodyY, planX - 3, bodyBottom, BORDER);
        renderPlan(g, planX, bodyY, bodyBottom);

        List<Row> rows = buildRows(transW);
        int contentH = rows.size() * LINE_H;
        lastMaxScroll = Math.max(0, contentH - viewH);
        if (pinBottom) scroll = lastMaxScroll;
        scroll = Math.clamp((long) scroll, 0, lastMaxScroll);

        g.enableScissor(transX, bodyY, transX + transW, bodyBottom);
        int y = bodyY - scroll;
        long t = System.currentTimeMillis();
        Set<String> done = doneIds();
        Set<String> failed = failedIds();
        for (Row row : rows) {
            if (y + LINE_H > bodyY && y < bodyBottom) {
                if (row.foldKey() != null) {                 // clickable fold toggle — glyph baked into text
                    txt(g, row.text, transX, y, row.color);
                } else if (row.toolIds() != null) {          // tool row — status icon + text
                    boolean anyRunning = row.toolIds().stream().anyMatch(id -> !done.contains(id));
                    boolean anyFail = row.toolIds().stream().anyMatch(failed::contains);
                    String icon = anyRunning ? SPIN[(int) ((t / 120) % 4)] : (anyFail ? "✗" : "✔");
                    int ic = anyRunning ? RUN : (anyFail ? FAIL : OK);
                    txt(g, Component.literal(icon), transX, y, ic);
                    txt(g, row.text, transX + 11, y, row.color);
                } else {
                    txt(g, row.text, transX, y, row.color);
                }
            }
            y += LINE_H;
        }
        g.disableScissor();

        // scrollbar — Cottage track + thumb sprites (was off-theme white fills)
        if (lastMaxScroll > 0) {
            int trackH = viewH;
            int thumbH = Math.max(12, trackH * viewH / (viewH + lastMaxScroll));
            int thumbY = bodyY + (trackH - thumbH) * scroll / lastMaxScroll;
            int sbX = transX + transW - 4;
            g.blitSprite(SCROLL_TRACK, sbX, bodyY, 4, viewH);
            g.blitSprite(SCROLL_THUMB, sbX, thumbY, 4, thumbH);
        }
    }

    /** Flatten the convo into render rows. Tool RESULT messages aren't drawn — they only mark the
     *  matching tool call done (via {@link #doneIds()}); the call line shows the spinner/check. */
    private List<Row> buildRows(int width) {
        List<Row> out = new ArrayList<>();
        Set<String> done = doneIds();
        Set<String> failed = failedIds();
        List<LlmToolCall> group = new ArrayList<>();        // a run of consecutive tool calls
        for (ConvoState.Msg msg : loop().convo().snapshot()) {
            switch (msg) {
                case ConvoState.Msg.User u -> {
                    flushTools(out, group, done, failed, width);
                    wrapPlain(out, u.content(), YOU, width);     // user = teal body, no label
                }
                case ConvoState.Msg.Assistant a -> {
                    AssistantTurn turn = a.turn();
                    if (turn.content() != null && !turn.content().isBlank()) {
                        flushTools(out, group, done, failed, width);   // spoken reply breaks the fold
                        addHeader(out, name, AI, width);         // bold name header on its OWN line
                        wrapPlain(out, turn.content(), AI, width);
                    }
                    group.addAll(turn.toolCalls());
                }
                case ConvoState.Msg.Tool ignored -> { /* result drives done/fail, not a row */ }
            }
        }
        flushTools(out, group, done, failed, width);
        if (loop().isCompacting()) {
            wrapPlain(out, "compacting history…", TXT_MUTED, width);
        }
        if (out.isEmpty()) {
            wrapPlain(out, "Say something to " + name + ".", TXT_FAINT, width);
        }
        return out;
    }

    /** Emit rows for a run of consecutive tool calls. A single call is always one plain row. A run of
     *  many stays EXPANDED while any is still running (live per-tool spinners) and AUTO-FOLDS to a muted
     *  "N steps · names" summary once all are done — unless the user clicked it open (keyed by the first
     *  id in {@link #expandedGroups}), in which case it shows a "▾" header + the tool rows. */
    private void flushTools(List<Row> out, List<LlmToolCall> group, Set<String> done, Set<String> failed, int width) {
        if (group.isEmpty()) return;
        if (group.size() == 1) {                                  // single tool — never folds
            addToolRow(out, group.get(0), width);
            group.clear();
            return;
        }
        String key = group.get(0).id();
        boolean running = group.stream().anyMatch(tc -> !done.contains(tc.id()));
        boolean expanded = running || expandedGroups.contains(key);
        if (!expanded) {                                          // folded summary (click to expand)
            List<String> names = new ArrayList<>();
            for (LlmToolCall tc : group) if (!names.contains(tc.name())) names.add(tc.name());
            boolean anyFail = group.stream().anyMatch(tc -> failed.contains(tc.id()));
            String summary = "▸ " + group.size() + " steps · " + String.join(" · ", names);
            out.add(new Row(colored(fitOneLine(summary, width - 2), anyFail ? FAIL : TOOL).getVisualOrderText(),
                    anyFail ? FAIL : TOOL, null, key));
        } else {
            if (!running) {                                       // manually expanded → collapsible header
                String hdr = "▾ " + group.size() + " steps";
                out.add(new Row(colored(hdr, TXT_MUTED).getVisualOrderText(), TXT_MUTED, null, key));
            }
            for (LlmToolCall tc : group) addToolRow(out, tc, width);
        }
        group.clear();
    }

    private void addToolRow(List<Row> out, LlmToolCall tc, int width) {
        FormattedCharSequence seq = colored(fitOneLine(toolLine(tc), width - 2 - 11), TOOL).getVisualOrderText();
        out.add(new Row(seq, TOOL, List.of(tc.id()), null));
    }

    /** Trim a string with an ellipsis so it fits one line of the given pixel width. */
    private String fitOneLine(String s, int pxWidth) {
        if (font.width(s) <= pxWidth) return s;
        while (s.length() > 1 && font.width(s + "…") > pxWidth) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private String toolLine(LlmToolCall tc) {
        String args = tc.arguments() == null ? "" : tc.arguments().replaceAll("\\s+", " ").trim();
        if (args.length() > TOOL_ARG_CHARS) args = args.substring(0, TOOL_ARG_CHARS) + "…";
        return tc.name() + "  " + args;
    }

    /** A bold name header on its OWN line (fixed format — never merges into the body). */
    private void addHeader(List<Row> out, String label, int color, int width) {
        var tc = net.minecraft.network.chat.TextColor.fromRgb(color & 0xFFFFFF);
        Component c = Component.literal(label).withStyle(s -> s.withColor(tc).withBold(true));
        for (FormattedCharSequence seq : font.split(c, width - 2)) {
            out.add(new Row(seq, color, null, null));
        }
    }

    /** A plain, regular-weight line (status hints) — colour baked into the style. */
    private void wrapPlain(List<Row> out, String text, int color, int width) {
        for (FormattedCharSequence seq : font.split(colored(text, color), width - 2)) {
            out.add(new Row(seq, color, null, null));
        }
    }

    private Set<String> doneIds() {
        Set<String> s = new HashSet<>();
        for (ConvoState.Msg m : loop().convo().snapshot()) {
            if (m instanceof ConvoState.Msg.Tool t) s.add(t.toolCallId());
        }
        return s;
    }

    private Set<String> failedIds() {
        Set<String> s = new HashSet<>();
        for (ConvoState.Msg m : loop().convo().snapshot()) {
            if (m instanceof ConvoState.Msg.Tool t && looksFailed(t.content())) s.add(t.toolCallId());
        }
        return s;
    }

    private static boolean looksFailed(String content) {
        if (content == null) return false;
        String c = content.replaceAll("\\s+", "");
        return c.contains("\"success\":false") || c.startsWith("ERROR") || c.contains("\"error\"");
    }

    /** Right-side PLAN panel: the companion's latest {@code todowrite}, with status glyphs. */
    private void renderPlan(GuiGraphics g, int x, int y, int bottom) {
        txt(g, Component.literal("PLAN"), x, y, TXT_MUTED);
        int ly = y + 13;
        JsonArray todos = latestPlan();
        if (todos == null || todos.isEmpty()) {
            txt(g, Component.literal("no plan yet"), x, ly, TXT_FAINT);
            return;
        }
        for (int i = 0; i < todos.size() && ly + LINE_H < bottom; i++) {
            if (!todos.get(i).isJsonObject()) continue;
            JsonObject it = todos.get(i).getAsJsonObject();
            String status = str(it, "status");
            String content = str(it, "content");
            String glyph = switch (status) { case "completed" -> "✔"; case "in_progress" -> "▸"; default -> "○"; };
            int color = switch (status) { case "completed" -> OK; case "in_progress" -> RUN; default -> TXT_FAINT; };
            txt(g, Component.literal(glyph), x, ly, color);
            // text hierarchy: in-progress = strong (current focus), completed = recede, pending = faint
            int textColor = switch (status) {
                case "in_progress" -> TXT;
                case "completed" -> TXT_MUTED;
                default -> TXT_FAINT;
            };
            List<FormattedCharSequence> lines = font.split(colored(content, textColor), PLAN_W - 14);
            int sub = 0;
            for (FormattedCharSequence seq : lines) {
                if (ly + LINE_H >= bottom) break;
                txt(g, seq, x + 10, ly, textColor);
                ly += LINE_H;
                if (++sub >= 2) break;   // cap each item at 2 lines
            }
            if (lines.isEmpty()) ly += LINE_H;
        }
    }

    /** Parse the most recent todowrite call's todos array, or null. */
    private JsonArray latestPlan() {
        JsonArray latest = null;
        for (ConvoState.Msg m : loop().convo().snapshot()) {
            if (m instanceof ConvoState.Msg.Assistant a) {
                for (LlmToolCall tc : a.turn().toolCalls()) {
                    if (!"todowrite".equals(tc.name())) continue;
                    try {
                        JsonObject args = JsonParser.parseString(tc.arguments()).getAsJsonObject();
                        if (args.has("todos") && args.get("todos").isJsonArray()) {
                            latest = args.getAsJsonArray("todos");
                        }
                    } catch (RuntimeException ignored) { /* keep the last good one */ }
                }
            }
        }
        return latest;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    /** The Items tab: a vanilla-inventory-style "companion sheet" — armor column + offhand, a live
     *  mouse-following portrait, the synced 2×2 craft grid + result, segmented heart/drumstick vitals,
     *  and the read-only checkerboard 3×9 storage + hotbar. Body data is fetched on demand via
     *  RequestInventoryPayload (backpack + craft + food); HP + equipment come off the live client entity. */
    private void renderItems(GuiGraphics g, int mouseX, int mouseY) {
        var snap = ClientTulpaInventory.get(uuid).orElse(null);
        AbstractClientPlayer e = ClientTulpaLookup.resolve(uuid);
        List<ItemStack> craft = snap != null ? snap.craft() : List.of();

        // Two centred columns: LEFT = big portrait + armor column + offhand; RIGHT = craft + vitals +
        // 3×9 storage + hotbar. Symmetric framing margins (no lopsided whitespace).
        final int STORAGE_W = 9 * 18;                     // 162 — the widest element (caps the band)
        final int COMP_W = 130 + STORAGE_W;               // left col (130) + right col (storage)
        final int COMP_H = 152;
        int startX = left + (PANEL_W - COMP_W) / 2;
        int cTop = top + HEADER_H + (PANEL_H - HEADER_H - COMP_H) / 2;
        int rightX = startX + 130;

        // -- LEFT: portrait socket, armor column + offhand (vertically centred against the portrait) --
        renderPortrait(g, e, startX + 22, cTop, 84, COMP_H, mouseX, mouseY);
        int armorTop = cTop + (COMP_H - 5 * 18) / 2;
        for (int i = 0; i < ARMOR.length; i++) {
            drawEquip(g, e, ARMOR[i], startX, armorTop + i * 18, mouseX, mouseY);
        }
        drawEquip(g, e, EquipmentSlot.OFFHAND, startX, armorTop + 4 * 18, mouseX, mouseY);

        // -- RIGHT top: synced 2×2 craft grid (+ arrow + result) --
        for (int i = 0; i < 4; i++) {
            int cx = rightX + (i % 2) * 18, cy = cTop + (i / 2) * 18;
            slotBg(g, SLOT_SPRITE, cx, cy);
            stackOn(g, i < craft.size() ? craft.get(i) : ItemStack.EMPTY, cx, cy, mouseX, mouseY);
        }
        txt(g, Component.literal("→"), rightX + 38, cTop + 13, TXT_MUTED);
        int resultX = rightX + 54, resultY = cTop + 9;
        slotBg(g, SLOT_SPRITE, resultX, resultY);
        stackOn(g, craft.size() > 4 ? craft.get(4) : ItemStack.EMPTY, resultX, resultY, mouseX, mouseY);

        // -- RIGHT mid: segmented hearts + drumsticks --
        if (e != null) renderStatRow(g, rightX, cTop + 46, e.getHealth(), e.getMaxHealth(),
                HEART_FULL, HEART_HALF, HEART_EMPTY);
        int food = (snap != null && snap.loaded()) ? snap.foodLevel() : 0;
        renderStatRow(g, rightX, cTop + 46 + ICON + 2, food, 20, FOOD_FULL, FOOD_HALF, FOOD_EMPTY);

        // -- RIGHT bottom: checkerboard 3×9 storage + hotbar --
        int storeY = cTop + 74;
        if (snap == null) {
            txt(g, Component.literal("loading…"), rightX, storeY + 4, TXT_FAINT);
            return;
        }
        if (!snap.loaded() || snap.items().isEmpty()) {
            txt(g, Component.literal("asleep — chat to wake it."), rightX, storeY + 4, TXT_FAINT);
            return;
        }
        List<ItemStack> items = snap.items();
        for (int i = 9; i < 36; i++) {                     // storage rows (slots 9..35)
            int col = (i - 9) % 9, row = (i - 9) / 9;
            int x = rightX + col * 18, y = storeY + row * 18;
            slotBg(g, ((col + row) & 1) == 0 ? SLOT_SPRITE : SLOT_ALT, x, y);
            stackOn(g, items.get(i), x, y, mouseX, mouseY);
        }
        int hotbarY = storeY + 3 * 18 + 6;                 // hotbar (slots 0..8)
        for (int i = 0; i < 9; i++) {
            int x = rightX + i * 18;
            slotBg(g, (i & 1) == 0 ? SLOT_SPRITE : SLOT_ALT, x, hotbarY);
            stackOn(g, items.get(i), x, hotbarY, mouseX, mouseY);
        }
    }

    private static net.minecraft.resources.ResourceLocation spr(String name) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.dwinovo.tulpa.Constants.MOD_ID, name);
    }
    private static final net.minecraft.resources.ResourceLocation SLOT_SPRITE = spr("slot");
    private static final net.minecraft.resources.ResourceLocation SLOT_ALT = spr("slot_alt");        // checkerboard
    /** Parchment frame (reuses the button sprite) behind text fields. */
    private static final net.minecraft.resources.ResourceLocation FIELD_SPRITE = spr("button");
    private static final net.minecraft.resources.ResourceLocation HEART_FULL = spr("heart_full");
    private static final net.minecraft.resources.ResourceLocation HEART_HALF = spr("heart_half");
    private static final net.minecraft.resources.ResourceLocation HEART_EMPTY = spr("heart_empty");
    private static final net.minecraft.resources.ResourceLocation FOOD_FULL = spr("food_full");
    private static final net.minecraft.resources.ResourceLocation FOOD_HALF = spr("food_half");
    private static final net.minecraft.resources.ResourceLocation FOOD_EMPTY = spr("food_empty");
    private static final net.minecraft.resources.ResourceLocation SCROLL_TRACK = spr("scroll_track");
    private static final net.minecraft.resources.ResourceLocation SCROLL_THUMB = spr("scroll_thumb");

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A rendered transcript line. {@code toolIds} non-null = a tool row (status icon = spinner/✔/✗).
     *  {@code foldKey} non-null = a clickable fold toggle (the group's first id); both null = plain text. */
    private record Row(FormattedCharSequence text, int color, List<String> toolIds, String foldKey) {}
}
