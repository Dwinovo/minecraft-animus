package com.dwinovo.animus.client.screen;

import com.dwinovo.animus.agent.llm.AnimusLlmClient;
import com.dwinovo.animus.agent.llm.ConvoState;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.LlmToolCall;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.agent.ClientAnimusLookup;
import com.dwinovo.animus.client.agent.EntityAgentLoop;
import com.dwinovo.animus.client.data.ClientAnimusInventory;
import com.dwinovo.animus.network.payload.RequestInventoryPayload;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.platform.services.IAnimusConfig;
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
import net.minecraft.client.player.AbstractClientPlayer;
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
 * The owner-facing companion panel: one tabbed screen per Animus (Chat / Items /
 * Settings), opened from the roster or hotkey. Resolves the companion lazily by
 * UUID so it works at any distance.
 *
 * <h2>Chat tab</h2>
 * A scrollable transcript on the left + a live PLAN panel on the right. Tool calls
 * show a spinner while running and a green check once their result lands — the raw
 * tool-result JSON is NOT shown (it only flips the call to done), keeping the chat
 * readable. The plan is the companion's latest {@code todowrite}.
 */
public final class AnimusScreen extends Screen {

    private enum Tab { CHAT, ITEMS, SETTINGS }

    // ---- layout ----
    private static final int PANEL_W = 380;
    private static final int PANEL_H = 232;
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
    /** Cottage panel chrome — a vanilla GUI sprite (assets/animus/textures/gui/sprites/panel.png,
     *  loaded into the GUI sprite atlas at resource-load), drawn with blitSprite like vanilla widgets. */
    private static final net.minecraft.resources.Identifier PANEL_SPRITE =
            net.minecraft.resources.Identifier.fromNamespaceAndPath(com.dwinovo.animus.Constants.MOD_ID, "panel");

    private static final String[] SPIN = {"|", "/", "-", "\\"};
    /** Armor column on the Items tab (top → bottom); offhand is drawn separately below it. */
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final UUID uuid;
    private final String name;
    private Tab tab = Tab.CHAT;

    private EditBox input;
    private SimpleButton sendButton;
    private SimpleButton stopButton;
    private SimpleButton compactButton;
    private String savedInput = "";

    // settings tab widgets
    private ProviderDropdown providerDropdown;
    private EditBox apiKeyInput;
    private EditBox modelInput;
    private EditBox baseUrlInput;
    private long savedFlashUntil;

    // Widgets are registered for EVENTS only (addWidget) and rendered MANUALLY at the end of the
    // frame, so they sit ON TOP of the panel background instead of being painted over by it (the
    // "dim fields" bug — the panel fill ran after the auto-rendered widgets).
    private final List<AbstractWidget> overlay = new ArrayList<>();
    /** API key is masked by default; the eye button toggles it. */
    private boolean showKey;

    // geometry resolved in init()
    private int left, top;
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

    private AnimusScreen(UUID uuid, String name) {
        super(Component.literal("Animus - " + name));
        this.uuid = uuid;
        this.name = name;
    }

    public static void open(UUID uuid, String name) {
        Minecraft.getInstance().setScreen(new AnimusScreen(uuid, name));
    }

    private EntityAgentLoop loop() {
        return AgentLoopRegistry.getOrCreate(uuid);
    }

    @Override
    protected void init() {
        this.left = (this.width - PANEL_W) / 2;
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
        switch (tab) {
            case CHAT -> buildChatWidgets();
            case SETTINGS -> buildSettingsWidgets();
            case ITEMS -> { /* no widgets */ }
        }
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
                inW - FIELD_INSET_X * 2, INPUT_H - FIELD_INSET_Y * 2, Component.literal("animus.chat.input"));
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
        IAnimusConfig cfg = Services.CONFIG;
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
        IAnimusConfig cfg = Services.CONFIG;
        cfg.setProvider(providerDropdown.selectedId());
        cfg.setApiKey(apiKeyInput.getValue());
        cfg.setModel(modelInput.getValue());
        cfg.setBaseUrl(baseUrlInput.getValue());
        cfg.save();
        AnimusLlmClient.reset();
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
        if (!AnimusLlmClient.isConfigured()) {
            com.dwinovo.animus.Constants.LOG.warn("[animus-chat] no apiKey; open Settings.");
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
        if ((k == 257 || k == 335) && input != null && input.isFocused()) {
            onSend();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        if (event.button() == 0) {
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

        // Cottage panel — 100% the GUI sprite (warm tan ground + dot-grid + sage band + warm-brown
        // border), drawn the vanilla way. No procedural draw.
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                PANEL_SPRITE, left, top, PANEL_W, PANEL_H);

        txt(g, Component.literal(name), left + PAD, top + 7, ON_BAND);   // name on the sage band
        renderTabs(g, mouseX, mouseY);

        if (compactButton != null) compactButton.active = loop().canCompact();
        if (stopButton != null) stopButton.active = loop().canInterrupt();

        switch (tab) {
            case CHAT -> renderChat(g);
            case ITEMS -> renderItems(g, mouseX, mouseY);
            case SETTINGS -> renderSettings(g, mouseX, mouseY);
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
        var snap = ClientAnimusInventory.get(uuid).orElse(null);
        AbstractClientPlayer e = ClientAnimusLookup.resolve(uuid);
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
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(com.dwinovo.animus.Constants.MOD_ID, name);
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
