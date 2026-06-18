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
    private static final int VITALS_H = 22;
    private static final int INPUT_H = 18;
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
    private static final int YOU = TH.reply();
    private static final int TOOL = TH.text();
    private static final int OK = TH.ok();
    private static final int RUN = TH.run();
    private static final int FAIL = TH.fail();
    private static final int GROUND = TH.ground();
    private static final int BAND = TH.band();
    private static final int DOT = TH.dot();

    private static final String[] SPIN = {"|", "/", "-", "\\"};
    private static final EquipmentSlot[] EQUIP = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

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

        input = new EditBox(font, inX, inputY, inW, INPUT_H, Component.literal("animus.chat.input"));
        input.setMaxLength(MAX_PROMPT);
        input.setBordered(false);
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
        EditBox e = new EditBox(font, x, y, w, 18, Component.literal(""));
        e.setMaxLength(max);
        e.setValue(value == null ? "" : value);
        e.setBordered(false);
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

        g.text(font, Component.literal("Provider"), x, y, TXT_MUTED);
        g.text(font, Component.literal("API Key"), x, y + 37, TXT_MUTED);
        g.text(font, Component.literal("Model"), x, y + 74, TXT_MUTED);
        g.text(font, Component.literal("Base URL"), x, y + 111, TXT_MUTED);

        if (savedFlashUntil > System.currentTimeMillis()) {
            g.text(font, Component.literal("✔ saved"), x, top + PANEL_H - PAD - 14, OK);
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
        }
        return super.mouseClicked(event, dbl);
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

        // BlockFrame "Cottage" chrome, drawn procedurally (pixel-identical to the sprite, but reliable
        // + theme-swappable): hard offset shadow, warm tan ground, faint dot-grid, sage header band,
        // thick warm-brown border.
        int s = 4;
        g.fill(left + s, top + s, left + PANEL_W + s, top + PANEL_H + s, BORDER);   // hard offset shadow
        g.fill(left, top, left + PANEL_W, top + PANEL_H, GROUND);                   // warm tan ground
        g.fill(left + 3, top + 3, left + PANEL_W - 3, top + HEADER_H, BAND);        // sage header band
        dotGrid(g, left + 5, top + HEADER_H + 3, left + PANEL_W - 4, top + PANEL_H - 4);
        SimpleButton.thickBorder(g, left, top, PANEL_W, PANEL_H, 3, BORDER);        // thick border
        g.fill(left, top + HEADER_H, left + PANEL_W, top + HEADER_H + 3, BORDER);   // header divider

        g.text(font, Component.literal(name), left + PAD, top + 7, ON_BAND);   // name on the sage band
        renderTabs(g, mouseX, mouseY);

        if (compactButton != null) compactButton.active = loop().canCompact();
        if (stopButton != null) stopButton.active = loop().canInterrupt();

        switch (tab) {
            case CHAT -> { renderVitals(g, mouseX, mouseY); renderChat(g); }
            case ITEMS -> renderItems(g, mouseX, mouseY);
            case SETTINGS -> renderSettings(g, mouseX, mouseY);
        }

        // Widgets render LAST, on top of the panel background (fixes the "dim fields" — the panel fill
        // used to paint over the auto-rendered widgets). Text fields are borderless EditBoxes, so draw
        // a parchment field background + border behind each before it renders its text.
        for (AbstractWidget w : overlay) {
            if (w instanceof EditBox eb) {
                g.fill(eb.getX(), eb.getY(), eb.getX() + eb.getWidth(), eb.getY() + eb.getHeight(), FIELD);
                SimpleButton.thickBorder(g, eb.getX(), eb.getY(), eb.getWidth(), eb.getHeight(), 2, BORDER);
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

    /** Faint BlockFrame dot-grid over the body ground. */
    private void dotGrid(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y += 12) {
            for (int x = x0; x < x1; x += 12) {
                g.fill(x, y, x + 1, y + 1, DOT);
            }
        }
    }

    private void renderTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String[] labels = {"Chat", "Items", "Settings"};
        for (int i = 0; i < 3; i++) {
            boolean active = tab == Tab.values()[i];
            boolean hover = mouseX >= tabX[i] && mouseX < tabX[i] + tabW[i]
                    && mouseY >= top && mouseY < top + HEADER_H;
            int color = (active || hover) ? ON_BAND : (0x00FFFFFF & ON_BAND) | 0xA0000000;   // dim on band
            g.text(font, Component.literal(labels[i]), tabX[i] + 5, top + 7, color);
            if (active) {                                                                     // gold CTA underline
                g.fill(tabX[i] + 3, top + HEADER_H - 4, tabX[i] + tabW[i] - 3, top + HEADER_H - 1, ACCENT);
            }
        }
    }

    /** Chat header vitals: just the HP bar (equipment + hunger live on the Items tab now). */
    private void renderVitals(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int y = top + HEADER_H + 4;
        AbstractClientPlayer e = ClientAnimusLookup.resolve(uuid);
        if (e == null) {
            g.text(font, Component.literal("(body out of view)"), left + PAD, y + 4, TXT_FAINT);
            return;
        }
        renderHpBar(g, left + PAD, y, e.getHealth(), e.getMaxHealth());
    }

    private void renderHpBar(GuiGraphicsExtractor g, int x, int y, float hp, float max) {
        int barW = 96, barH = 5, barY = y + 8;
        g.text(font, Component.literal("HP " + fmt(hp) + "/" + fmt(max)), x, y - 1, 0xFFFF8A8A);
        g.fill(x, barY, x + barW, barY + barH, 0xFF3A1414);
        int fill = (int) (barW * Math.clamp(hp / max, 0f, 1f));
        if (fill > 0) g.fill(x, barY, x + fill, barY + barH, 0xFFE0473C);
        g.outline(x - 1, barY - 1, barW + 2, barH + 2, BORDER);
    }

    private void renderHungerBar(GuiGraphicsExtractor g, int x, int y, int food) {
        int barW = 96, barH = 5, barY = y + 8;
        g.text(font, Component.literal("Food " + food + "/20"), x, y - 1, 0xFFD8B36A);
        g.fill(x, barY, x + barW, barY + barH, 0xFF2E2410);
        int fill = (int) (barW * Math.clamp(food / 20f, 0f, 1f));
        if (fill > 0) g.fill(x, barY, x + fill, barY + barH, 0xFFC88A3A);
        g.outline(x - 1, barY - 1, barW + 2, barH + 2, BORDER);
    }

    /** The six equipment slots, read off the live client entity (equipment IS client-synced). */
    private void renderEquipment(GuiGraphicsExtractor g, AbstractClientPlayer e, int x, int y,
                                 int mouseX, int mouseY) {
        for (EquipmentSlot slot : EQUIP) {
            g.fill(x, y, x + 16, y + 16, 0x40000000);
            g.outline(x, y, 16, 16, BORDER);
            ItemStack st = e.getItemBySlot(slot);
            if (!st.isEmpty()) {
                g.item(st, x, y);
                g.itemDecorations(font, st, x, y);
                if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                    g.setTooltipForNextFrame(font, st, mouseX, mouseY);
                }
            }
            x += 18;
        }
    }

    // ---- chat transcript + plan ----

    private void renderChat(GuiGraphicsExtractor g) {
        int bodyY = top + HEADER_H + VITALS_H + 4;
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
                if (row.toolId != null) {
                    boolean isDone = done.contains(row.toolId);
                    boolean isFail = failed.contains(row.toolId);
                    String icon = isDone ? (isFail ? "✗" : "✔") : SPIN[(int) ((t / 120) % 4)];
                    int ic = isDone ? (isFail ? FAIL : OK) : RUN;
                    g.text(font, Component.literal(icon), transX, y, ic);
                    g.text(font, row.text, transX + 11, y, row.color);
                } else {
                    g.text(font, row.text, transX, y, row.color);
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
        for (ConvoState.Msg msg : loop().convo().snapshot()) {
            switch (msg) {
                case ConvoState.Msg.User u -> wrap(out, "you  " + u.content(), YOU, width, null);
                case ConvoState.Msg.Assistant a -> {
                    AssistantTurn turn = a.turn();
                    if (turn.content() != null && !turn.content().isBlank()) {
                        wrap(out, turn.content(), TXT, width, null);
                    }
                    for (LlmToolCall tc : turn.toolCalls()) {
                        out.add(new Row(FormattedCharSequence.forward(toolLine(tc), net.minecraft.network.chat.Style.EMPTY),
                                TOOL, tc.id()));
                    }
                }
                case ConvoState.Msg.Tool ignored -> { /* result drives done/fail, not a row */ }
            }
        }
        if (loop().isCompacting()) {
            wrap(out, "compacting history…", TXT_MUTED, width, null);
        }
        if (out.isEmpty()) {
            wrap(out, "Say something to " + name + ".", TXT_FAINT, width, null);
        }
        return out;
    }

    private String toolLine(LlmToolCall tc) {
        String args = tc.arguments() == null ? "" : tc.arguments().replaceAll("\\s+", " ").trim();
        if (args.length() > TOOL_ARG_CHARS) args = args.substring(0, TOOL_ARG_CHARS) + "…";
        return tc.name() + "  " + args;
    }

    private void wrap(List<Row> out, String text, int color, int width, String toolId) {
        for (FormattedCharSequence seq : font.split(Component.literal(text), width - 2)) {
            out.add(new Row(seq, color, toolId));
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
        g.text(font, Component.literal("PLAN"), x, y, TXT_MUTED);
        int ly = y + 13;
        JsonArray todos = latestPlan();
        if (todos == null || todos.isEmpty()) {
            g.text(font, Component.literal("no plan yet"), x, ly, TXT_FAINT);
            return;
        }
        for (int i = 0; i < todos.size() && ly + LINE_H < bottom; i++) {
            if (!todos.get(i).isJsonObject()) continue;
            JsonObject it = todos.get(i).getAsJsonObject();
            String status = str(it, "status");
            String content = str(it, "content");
            String glyph = switch (status) { case "completed" -> "✔"; case "in_progress" -> "▸"; default -> "○"; };
            int color = switch (status) { case "completed" -> OK; case "in_progress" -> RUN; default -> TXT_MUTED; };
            g.text(font, Component.literal(glyph), x, ly, color);
            // one wrapped line of the content beside the glyph
            List<FormattedCharSequence> lines = font.split(Component.literal(content), PLAN_W - 14);
            int sub = 0;
            for (FormattedCharSequence seq : lines) {
                if (ly + LINE_H >= bottom) break;
                g.text(font, seq, x + 10, ly, status.equals("pending") ? TXT_MUTED : TXT);
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

    /** The Items tab: vitals (HP + hunger) + equipment + the read-only 36-slot backpack, all fetched
     *  via RequestInventoryPayload (food + items) and the live entity (HP + equipment). */
    private void renderItems(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var snap = ClientAnimusInventory.get(uuid).orElse(null);
        AbstractClientPlayer e = ClientAnimusLookup.resolve(uuid);

        // -- vitals row: HP + hunger (left), equipment (right) --
        int vy = top + HEADER_H + 6;
        if (e != null) renderHpBar(g, left + PAD, vy, e.getHealth(), e.getMaxHealth());
        if (snap != null && snap.loaded()) renderHungerBar(g, left + PAD + 110, vy, snap.foodLevel());
        if (e != null) {
            renderEquipment(g, e, left + PANEL_W - PAD - EQUIP.length * 18, vy + 1, mouseX, mouseY);
        }

        // -- backpack --
        int y = vy + 24;
        g.text(font, Component.literal("Backpack (read-only)"), left + PAD, y, TXT_MUTED);
        if (snap == null) {
            g.text(font, Component.literal("loading…"), left + PAD, y + 16, TXT_FAINT);
            return;
        }
        if (!snap.loaded() || snap.items().isEmpty()) {
            g.text(font, Component.literal("asleep / out of view — chat to wake it."),
                    left + PAD, y + 16, TXT_FAINT);
            return;
        }
        List<ItemStack> items = snap.items();
        int gx = left + (PANEL_W - 9 * 18) / 2;       // centre the 9-wide grid
        int gy = y + 16;
        for (int i = 9; i < 36; i++) {                 // storage rows (slots 9..35)
            int col = (i - 9) % 9, row = (i - 9) / 9;
            drawSlot(g, items, i, gx + col * 18, gy + row * 18, mouseX, mouseY);
        }
        int hotbarY = gy + 3 * 18 + 6;                 // hotbar (slots 0..8)
        for (int i = 0; i < 9; i++) {
            drawSlot(g, items, i, gx + i * 18, hotbarY, mouseX, mouseY);
        }
    }

    private void drawSlot(GuiGraphicsExtractor g, List<ItemStack> items, int index,
                          int x, int y, int mouseX, int mouseY) {
        g.fill(x, y, x + 16, y + 16, 0x40000000);
        g.outline(x, y, 16, 16, BORDER);
        ItemStack st = index < items.size() ? items.get(index) : ItemStack.EMPTY;
        if (!st.isEmpty()) {
            g.item(st, x, y);
            g.itemDecorations(font, st, x, y);
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                g.setTooltipForNextFrame(font, st, mouseX, mouseY);
            }
        }
    }

    private static String fmt(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Row(FormattedCharSequence text, int color, String toolId) {}
}
