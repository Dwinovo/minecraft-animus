package com.dwinovo.tulpa.client.screen;

import com.dwinovo.tulpa.agent.llm.TulpaLlmClient;
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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
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
    private static net.minecraft.resources.Identifier railSpr(String n) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(com.dwinovo.tulpa.Constants.MOD_ID, n);
    }
    /** rail + panel composited into ONE sprite (continuous header, no gap; panel's left border = divider). */
    private static final net.minecraft.resources.Identifier WORKSPACE_SPRITE = railSpr("workspace");
    private static final net.minecraft.resources.Identifier AVATAR_FRAME = railSpr("avatar_frame");
    private static final net.minecraft.resources.Identifier AVATAR_FRAME_ACTIVE = railSpr("avatar_frame_active");
    private static final net.minecraft.resources.Identifier SUMMON_SPRITE = railSpr("summon");
    private static final net.minecraft.resources.Identifier SUMMON_ACTIVE = railSpr("summon_active");

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

    // settings tab widgets
    private ProviderDropdown providerDropdown;
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
        apiKeyInput = modelInput = baseUrlInput = null;
        summonInput = null;
        if (summoning) { buildSummonField(); return; }
        switch (tab) {
            case CHAT -> { if (uuid != null) buildChatWidgets(); }
            case SETTINGS -> buildSettingsWidgets();
            case ITEMS -> { /* no widgets */ }
        }
    }

    private void buildSummonField() {
        int y = top + HEADER_H + 24;
        summonInput = new EditBox(font, left + PAD + FIELD_INSET_X, y + FIELD_INSET_Y,
                PANEL_W - PAD * 2 - FIELD_INSET_X * 2, 18 - FIELD_INSET_Y * 2, Component.literal(""));
        summonInput.setMaxLength(com.dwinovo.tulpa.network.payload.SummonRequestPayload.MAX_NAME);
        summonInput.setBordered(false);
        summonInput.setTextShadow(false);
        summonInput.setTextColor(TXT);
        summonInput.setHint(Component.literal("New companion name…"));
        add(summonInput);
        setInitialFocus(summonInput);
    }

    /** Register a widget for EVENTS only; it's rendered manually (on top of the panel) in {@link
     *  #extractRenderState}. */
    private <T extends AbstractWidget> T add(T w) {
        addWidget(w);
        overlay.add(w);
        return w;
    }

    // Shadowless text — BlockFrame is flat, and a drop shadow on DARK text over a LIGHT ground makes
    // the glyph merge with its own shadow ("smudged"). This build's shadowless path ignores the colour
    // PARAM, so we bake the colour into the text's Style instead.
    private void txt(GuiGraphicsExtractor g, Component c, int x, int y, int color) {
        g.text(font, c.copy().withStyle(s -> s.withColor(
                net.minecraft.network.chat.TextColor.fromRgb(color & 0xFFFFFF))), x, y, -1, false);
    }

    /** The FormattedCharSequence must already carry its colour (see {@link #colored}). */
    private void txt(GuiGraphicsExtractor g, FormattedCharSequence c, int x, int y, int color) {
        g.text(font, c, x, y, -1, false);
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

        input = new EditBox(font, inX + FIELD_INSET_X, inputY + FIELD_INSET_Y,
                inW - FIELD_INSET_X * 2, INPUT_H - FIELD_INSET_Y * 2, Component.literal("tulpa.chat.input"));
        input.setMaxLength(MAX_PROMPT);
        input.setBordered(false);
        input.setTextShadow(false);
        input.setTextColor(TXT);
        input.setHint(Component.literal("Talk to " + name + "…"));
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
        rebuild();
    }

    // ---- settings tab ----

    private void buildSettingsWidgets() {
        ITulpaConfig cfg = Services.CONFIG;
        int x = left + PAD, w = PANEL_W - PAD * 2;
        int y = top + HEADER_H + 10;

        providerDropdown = new ProviderDropdown(cfg.getProvider());
        providerDropdown.setBounds(x, y + 11, w, 18);

        // API key: leave room for the eye button, and mask the value until revealed.
        int eyeW = 22;
        int y2 = y + 37;
        apiKeyInput = field(x, y2 + 11, w - eyeW - 2, 512, cfg.getApiKey());
        apiKeyInput.addFormatter((text, idx) -> showKey
                ? FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY)
                : FormattedCharSequence.forward("•".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
        add(new SimpleButton(x + w - eyeW, y2 + 11, eyeW, 18,
                Component.literal(showKey ? "隐" : "见"),
                b -> { showKey = !showKey; b.setMessage(Component.literal(showKey ? "隐" : "见")); }));

        int y3 = y2 + 37;
        modelInput = field(x, y3 + 11, w, 128, cfg.getModel());
        int y4 = y3 + 37;
        baseUrlInput = field(x, y4 + 11, w, 256, cfg.getBaseUrl());

        int saveW = 64;
        add(new SimpleButton(left + PANEL_W - PAD - saveW, top + PANEL_H - PAD - 18,
                saveW, 18, Component.literal("Save"), b -> onSaveSettings()));
    }

    private EditBox field(int x, int y, int w, int max, String value) {
        EditBox e = new EditBox(font, x + FIELD_INSET_X, y + FIELD_INSET_Y,
                w - FIELD_INSET_X * 2, 18 - FIELD_INSET_Y * 2, Component.literal(""));
        e.setMaxLength(max);
        e.setValue(value == null ? "" : value);
        e.setBordered(false);
        e.setTextShadow(false);
        e.setTextColor(TXT);
        add(e);
        return e;
    }

    private void onSaveSettings() {
        ITulpaConfig cfg = Services.CONFIG;
        cfg.setProvider(providerDropdown.selectedId());
        cfg.setApiKey(apiKeyInput.getValue());
        cfg.setModel(modelInput.getValue());
        cfg.setBaseUrl(baseUrlInput.getValue());
        cfg.save();
        TulpaLlmClient.reset();
        savedFlashUntil = System.currentTimeMillis() + 1500;
    }

    private void renderSettings(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = left + PAD;
        int y = top + HEADER_H + 10;
        LlmProviders.Option opt = LlmProviders.byId(providerDropdown.selectedId());
        if (modelInput != null) modelInput.setHint(Component.literal(opt.defaultModel()));
        if (baseUrlInput != null) baseUrlInput.setHint(Component.literal(opt.defaultBaseUrl()));

        txt(g, Component.literal("Provider"), x, y, TXT_MUTED);
        txt(g, Component.literal("API Key"), x, y + 37, TXT_MUTED);
        txt(g, Component.literal("Model"), x, y + 74, TXT_MUTED);
        txt(g, Component.literal("Base URL"), x, y + 111, TXT_MUTED);

        if (savedFlashUntil > System.currentTimeMillis()) {
            txt(g, Component.literal("✔ saved"), x, top + PANEL_H - PAD - 14, OK);
        }
        // the dropdown itself is rendered in extractRenderState, AFTER the widgets (open list on top)
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
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (summoning) {
            if (k == 257 || k == 335) { doSummon(); return true; }    // Enter
            if (k == 256) { summoning = false; rebuild(); return true; } // Esc cancels (doesn't close panel)
            return super.keyPressed(event);
        }
        if ((k == 257 || k == 335) && input != null && input.isFocused()) {
            onSend();
            return true;
        }
        return super.keyPressed(event);
    }

    private void doSummon() {
        String n = summonInput == null ? "" : summonInput.getValue().trim();
        if (n.isEmpty()) return;
        Services.NETWORK.sendToServer(new com.dwinovo.tulpa.network.payload.SummonRequestPayload(n));
        summoning = false;
        rebuild();   // the new companion arrives via CompanionListPayload — click its avatar to open
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        if (event.button() == 0) {
            if (railPlusAt((int) event.x(), (int) event.y())) {   // + → start the summon name prompt
                summoning = !summoning;
                rebuild();
                return true;
            }
            int rail = railIndexAt((int) event.x(), (int) event.y());
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
            if (tab == Tab.SETTINGS && providerDropdown != null
                    && providerDropdown.mouseClicked(event.x(), event.y())) {
                return true;
            }
            int my = (int) event.y();
            if (my >= top && my < top + HEADER_H) {
                for (int i = 0; i < 3; i++) {
                    if (event.x() >= tabX[i] && event.x() < tabX[i] + tabW[i]) {
                        selectTab(Tab.values()[i]);
                        return true;
                    }
                }
            }
            if (tab == Tab.CHAT && toggleFoldAt((int) event.x(), my)) return true;
        }
        return super.mouseClicked(event, dbl);
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
        if (tab == Tab.CHAT && sy != 0) {
            scroll = Math.clamp((long) (scroll - sy * LINE_H * 3), 0, lastMaxScroll);
            pinBottom = scroll >= lastMaxScroll;
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    // ---- render ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        // ONE merged Cottage sprite: left rail column + panel, continuous header, no gap.
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                WORKSPACE_SPRITE, railX, top, RAIL_W + PANEL_W, PANEL_H);
        renderRail(g, mouseX, mouseY);   // avatars + status + summon tile on the rail column

        txt(g, Component.literal(name == null ? "Tulpa" : name), left + PAD, top + 7, ON_BAND);
        if (uuid != null && ClientDeaths.isDead(uuid)) {        // active companion dead — respawn countdown
            long rem = ClientDeaths.remainingMs(uuid);
            txt(g, Component.literal("· 复活中 " + (int) Math.ceil(rem / 1000.0) + "s"),
                    left + PAD + font.width(name == null ? "Tulpa" : name) + 6, top + 7, ON_BAND);
        }
        renderTabs(g, mouseX, mouseY);

        if (summoning) {
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
                g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                        FIELD_SPRITE, eb.getX() - FIELD_INSET_X, eb.getY() - FIELD_INSET_Y,
                        eb.getWidth() + FIELD_INSET_X * 2, eb.getHeight() + FIELD_INSET_Y * 2);
            }
        }
        for (AbstractWidget w : overlay) {
            w.extractRenderState(g, mouseX, mouseY, partial);
        }
        // The provider dropdown's open list must sit above even the fields.
        if (tab == Tab.SETTINGS && providerDropdown != null) {
            providerDropdown.render(g, font, mouseX, mouseY);
        }
    }

    // ---- left companion rail ----

    /** The folded-in roster (on the merged sprite's rail column): one avatar head per companion below the
     *  green header, active one framed gold, a status dot each, + tile at the bottom. */
    private void renderRail(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var pipe = net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;
        List<TulpaRoster.Entry> entries = TulpaRoster.instance().entries();
        int ax = railX + (RAIL_W - RAIL_AV) / 2;
        for (int i = 0; i < entries.size(); i++) {
            int ay = top + 8 + i * RAIL_SLOT;                        // rail has no header — start near the top
            if (ay + RAIL_AV > top + PANEL_H - PAD - RAIL_SLOT) break;   // reserve the bottom + slot
            TulpaRoster.Entry e = entries.get(i);
            boolean active = e.uuid().equals(uuid);
            // textured socket behind the head (gold-bordered when active), then the avatar, then a status LED
            g.blitSprite(pipe, active ? AVATAR_FRAME_ACTIVE : AVATAR_FRAME, ax - 2, ay - 2, RAIL_AV + 4, RAIL_AV + 4);
            PlayerFaceExtractor.extractRenderState(g, skinFor(e.uuid()), ax, ay, RAIL_AV);
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
        }
        // "+" summon tile (baked "+" glyph), pinned to the rail bottom
        int py = top + PANEL_H - PAD - RAIL_AV;
        g.blitSprite(pipe, summoning ? SUMMON_ACTIVE : SUMMON_SPRITE, ax, py, RAIL_AV, RAIL_AV);
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
        for (int i = 0; i < entries.size(); i++) {
            int ay = top + 8 + i * RAIL_SLOT;
            if (ay + RAIL_AV > top + PANEL_H - PAD - RAIL_SLOT) break;
            if (my >= ay && my < ay + RAIL_AV) return i;
        }
        return -1;
    }

    private void emptyHint(GuiGraphicsExtractor g) {
        txt(g, Component.literal("No companions. Click + to summon one."),
                left + PAD, top + HEADER_H + 10, TXT_FAINT);
    }

    private void renderTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
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
    private void renderStatRow(GuiGraphicsExtractor g, int x, int y, float value, float max,
                               net.minecraft.resources.Identifier full,
                               net.minecraft.resources.Identifier half,
                               net.minecraft.resources.Identifier empty) {
        var pipe = net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;
        int units = Math.max(1, (int) Math.ceil(max / 2f));
        for (int i = 0; i < units; i++) {
            int ix = x + i * ICON_STEP;
            g.blitSprite(pipe, empty, ix, y, ICON, ICON);
            float v = value - i * 2f;
            if (v >= 2f)      g.blitSprite(pipe, full, ix, y, ICON, ICON);
            else if (v >= 1f) g.blitSprite(pipe, half, ix, y, ICON, ICON);
        }
    }

    /** Live mouse-following 3D portrait of the companion — the body IS a client player entity, so the
     *  vanilla player renderer draws it for free. Sits in a recessed socket (slot_alt stretched). */
    private void renderPortrait(GuiGraphicsExtractor g, AbstractClientPlayer e,
                                int x, int y, int w, int h, int mouseX, int mouseY) {
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, SLOT_ALT, x, y, w, h);
        if (e == null) return;
        int scale = (int) (h * 0.45f);
        net.minecraft.client.gui.screens.inventory.InventoryScreen.extractEntityInInventoryFollowsMouse(
                g, x + 2, y + 2, x + w - 2, y + h - 2, scale, 0.0625f,
                (float) mouseX, (float) mouseY, e);
    }

    private void slotBg(GuiGraphicsExtractor g, net.minecraft.resources.Identifier sprite, int x, int y) {
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, sprite, x, y, 16, 16);
    }

    private void stackOn(GuiGraphicsExtractor g, ItemStack st, int x, int y, int mouseX, int mouseY) {
        if (st == null || st.isEmpty()) return;
        g.item(st, x, y);
        g.itemDecorations(font, st, x, y);
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            g.setTooltipForNextFrame(font, st, mouseX, mouseY);
        }
    }

    /** One equipment/armor socket, read off the live client entity (equipment IS client-synced). */
    private void drawEquip(GuiGraphicsExtractor g, AbstractClientPlayer e, EquipmentSlot slot,
                           int x, int y, int mouseX, int mouseY) {
        slotBg(g, SLOT_SPRITE, x, y);
        if (e != null) stackOn(g, e.getItemBySlot(slot), x, y, mouseX, mouseY);
    }

    // ---- chat transcript + plan ----

    private void renderChat(GuiGraphicsExtractor g) {
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

        // scrollbar thumb
        if (lastMaxScroll > 0) {
            int trackH = viewH;
            int thumbH = Math.max(12, trackH * viewH / (viewH + lastMaxScroll));
            int thumbY = bodyY + (trackH - thumbH) * scroll / lastMaxScroll;
            int sbX = transX + transW - 2;
            g.fill(sbX, bodyY, sbX + 2, bodyBottom, 0x30FFFFFF);
            g.fill(sbX, thumbY, sbX + 2, thumbY + thumbH, 0x80FFFFFF);
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
    private void renderPlan(GuiGraphicsExtractor g, int x, int y, int bottom) {
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
    private void renderItems(GuiGraphicsExtractor g, int mouseX, int mouseY) {
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

    private static net.minecraft.resources.Identifier spr(String name) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(com.dwinovo.tulpa.Constants.MOD_ID, name);
    }
    private static final net.minecraft.resources.Identifier SLOT_SPRITE = spr("slot");
    private static final net.minecraft.resources.Identifier SLOT_ALT = spr("slot_alt");        // checkerboard
    /** Parchment frame (reuses the button sprite) behind text fields. */
    private static final net.minecraft.resources.Identifier FIELD_SPRITE = spr("button");
    private static final net.minecraft.resources.Identifier HEART_FULL = spr("heart_full");
    private static final net.minecraft.resources.Identifier HEART_HALF = spr("heart_half");
    private static final net.minecraft.resources.Identifier HEART_EMPTY = spr("heart_empty");
    private static final net.minecraft.resources.Identifier FOOD_FULL = spr("food_full");
    private static final net.minecraft.resources.Identifier FOOD_HALF = spr("food_half");
    private static final net.minecraft.resources.Identifier FOOD_EMPTY = spr("food_empty");

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A rendered transcript line. {@code toolIds} non-null = a tool row (status icon = spinner/✔/✗).
     *  {@code foldKey} non-null = a clickable fold toggle (the group's first id); both null = plain text. */
    private record Row(FormattedCharSequence text, int color, List<String> toolIds, String foldKey) {}
}
