package com.dwinovo.animus.client.screen;

import com.dwinovo.animus.agent.llm.AnimusLlmClient;
import com.dwinovo.animus.agent.llm.ConvoState;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.LlmToolCall;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.agent.ClientAnimusLookup;
import com.dwinovo.animus.client.agent.EntityAgentLoop;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

    // ---- palette (clean dark, JEI/REI-ish) ----
    private static final int BG = 0xF00E1116;
    private static final int BORDER = 0xFF2B313B;
    private static final int ACCENT = 0xFF4F8CC9;
    private static final int TXT = 0xFFE6E8EB;
    private static final int TXT_MUTED = 0xFF8A929C;
    private static final int TXT_FAINT = 0xFF5C636D;
    private static final int YOU = 0xFF6FC3FF;
    private static final int TOOL = 0xFF7FD4C8;
    private static final int OK = 0xFF74D17A;
    private static final int RUN = 0xFFE3C25E;
    private static final int FAIL = 0xFFE57373;

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

    // geometry resolved in init()
    private int left, top;
    private final int[] tabX = new int[3];   // left x of each tab label, for click hit-testing
    private final int[] tabW = new int[3];

    // chat transcript scroll
    private int scroll;            // px scrolled down from the top of the content
    private boolean pinBottom = true;
    private int lastMaxScroll;

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

    /** Rebuild the widgets for the active tab (chat owns the input row; other tabs have none). */
    private void rebuild() {
        if (input != null) savedInput = input.getValue();
        clearWidgets();
        input = null;
        sendButton = stopButton = compactButton = null;
        if (tab == Tab.CHAT) buildChatWidgets();
    }

    private void buildChatWidgets() {
        int inputY = top + PANEL_H - INPUT_H - PAD;
        int compactW = 26;
        int sendW = 42;
        int stopW = 22;
        int inX = left + PAD + compactW + 4;
        int inW = PANEL_W - PAD * 2 - compactW - sendW - stopW - 12;

        compactButton = new SimpleButton(left + PAD, inputY, compactW, INPUT_H,
                Component.literal("⤬"), b -> loop().requestCompact());
        compactButton.active = loop().canCompact();
        addRenderableWidget(compactButton);

        input = new EditBox(font, inX, inputY, inW, INPUT_H, Component.literal("animus.chat.input"));
        input.setMaxLength(MAX_PROMPT);
        input.setHint(Component.literal("Talk to " + name + "…"));
        if (!savedInput.isEmpty()) { input.setValue(savedInput); savedInput = ""; }
        addRenderableWidget(input);
        setInitialFocus(input);

        sendButton = new SimpleButton(inX + inW + 4, inputY, sendW, INPUT_H,
                Component.literal("Send"), b -> onSend());
        addRenderableWidget(sendButton);

        stopButton = new SimpleButton(inX + inW + 4 + sendW + 4, inputY, stopW, INPUT_H,
                Component.literal("■"), b -> loop().abort());
        stopButton.active = loop().canInterrupt();
        addRenderableWidget(stopButton);
    }

    private void selectTab(Tab t) {
        if (t == Tab.SETTINGS) {                      // settings is still its own screen (stage 1)
            if (input != null) savedInput = input.getValue();
            Minecraft.getInstance().setScreen(new SettingsScreen(this));
            return;
        }
        if (t == tab) return;
        tab = t;
        scroll = 0;
        pinBottom = true;
        rebuild();
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

        g.fill(left, top, left + PANEL_W, top + PANEL_H, BG);
        g.outline(left, top, PANEL_W, PANEL_H, BORDER);
        g.fill(left, top + HEADER_H - 1, left + PANEL_W, top + HEADER_H, BORDER);   // header divider

        g.text(font, Component.literal(name), left + PAD, top + 7, TXT);
        renderTabs(g, mouseX, mouseY);

        if (compactButton != null) compactButton.active = loop().canCompact();
        if (stopButton != null) stopButton.active = loop().canInterrupt();

        switch (tab) {
            case CHAT -> { renderVitals(g, mouseX, mouseY); renderChat(g); }
            case ITEMS -> { renderVitals(g, mouseX, mouseY); renderItemsPlaceholder(g); }
            case SETTINGS -> { /* opened as a sub-screen */ }
        }
    }

    private void renderTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String[] labels = {"Chat", "Items", "Settings"};
        for (int i = 0; i < 3; i++) {
            boolean active = tab == Tab.values()[i];
            boolean hover = mouseX >= tabX[i] && mouseX < tabX[i] + tabW[i]
                    && mouseY >= top && mouseY < top + HEADER_H;
            int color = active ? TXT : (hover ? TXT : TXT_MUTED);
            g.text(font, Component.literal(labels[i]), tabX[i] + 5, top + 7, color);
            if (active) {
                g.fill(tabX[i] + 3, top + HEADER_H - 2, tabX[i] + tabW[i] - 3, top + HEADER_H, ACCENT);
            }
        }
    }

    /** HP bar (left) + equipment slots (right), read off the live client entity. */
    private void renderVitals(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int y = top + HEADER_H + 4;
        AbstractClientPlayer e = ClientAnimusLookup.resolve(uuid);
        if (e == null) {
            g.text(font, Component.literal("(body out of view)"), left + PAD, y + 4, TXT_FAINT);
            return;
        }
        float hp = e.getHealth(), max = e.getMaxHealth();
        int barW = 96, barH = 5, barX = left + PAD, barY = y + 8;
        g.text(font, Component.literal("HP " + fmt(hp) + "/" + fmt(max)), barX, y - 1, 0xFFFF8A8A);
        g.fill(barX, barY, barX + barW, barY + barH, 0xFF3A1414);
        int fill = (int) (barW * Math.clamp(hp / max, 0f, 1f));
        if (fill > 0) g.fill(barX, barY, barX + fill, barY + barH, 0xFFE0473C);
        g.outline(barX - 1, barY - 1, barW + 2, barH + 2, BORDER);

        int step = 18, x = left + PANEL_W - PAD - EQUIP.length * step, sy = y;
        for (EquipmentSlot slot : EQUIP) {
            g.fill(x, sy, x + 16, sy + 16, 0x40000000);
            g.outline(x, sy, 16, 16, BORDER);
            ItemStack st = e.getItemBySlot(slot);
            if (!st.isEmpty()) {
                g.item(st, x, sy);
                g.itemDecorations(font, st, x, sy);
                if (mouseX >= x && mouseX < x + 16 && mouseY >= sy && mouseY < sy + 16) {
                    g.setTooltipForNextFrame(font, st, mouseX, mouseY);
                }
            }
            x += step;
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

    private void renderItemsPlaceholder(GuiGraphicsExtractor g) {
        int y = top + HEADER_H + VITALS_H + 8;
        g.text(font, Component.literal("Inventory"), left + PAD, y, TXT_MUTED);
        g.text(font, Component.literal("(read-only view — coming next)"), left + PAD, y + 14, TXT_FAINT);
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
