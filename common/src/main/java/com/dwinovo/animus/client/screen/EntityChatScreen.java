package com.dwinovo.animus.client.screen;

import com.dwinovo.animus.agent.llm.AnimusLlmClient;
import com.dwinovo.animus.agent.llm.ConvoState;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.LlmToolCall;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.agent.EntityAgentLoop;
import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Owner-facing chat GUI bound to ONE Animus entity. Opened on owner
 * right-click ({@code AnimusInteractHandler}) or from the Units tab Chat
 * button. Talks directly to that entity's {@link EntityAgentLoop} — the
 * single-layer agent that drives the body.
 *
 * <h2>Why per-entity</h2>
 * Each Animus carries its own conversation. The owner types here, the
 * entity's loop runs the LLM with the player's own API key and dispatches
 * tool calls to the server. The entity's text replies show up in the
 * scrollback. There is no PlayerAgent intermediary — this is the
 * rolled-back single-layer design (see {@link EntityAgentLoop}).
 *
 * <h2>Client-only class</h2>
 * Lives in {@code common} because {@code Screen} is on the shared client
 * jar. The dedicated server JVM never loads this class —
 * {@link com.dwinovo.animus.entity.interact.AnimusInteractHandler} guards
 * the {@link #open} call with {@code level().isClientSide()}.
 */
public final class EntityChatScreen extends Screen {

    private static final int CONTENT_WIDTH = 320;
    private static final int CONTENT_HEIGHT = 220;
    private static final int INPUT_HEIGHT = 18;
    private static final int BUTTON_HEIGHT = 18;
    private static final int SEND_WIDTH = 50;
    private static final int SETTINGS_WIDTH = 60;
    private static final int PADDING = 6;
    private static final int LINE_HEIGHT = 10;
    private static final int MAX_PROMPT_LENGTH = 1024;
    private static final int MAX_LINES_PER_MESSAGE = 8;
    private static final int MAX_TOTAL_LINES = 200;

    private final int entityId;
    private final String targetName;

    private EditBox input;
    private String savedInputText = "";

    private int left, top;
    private int historyX, historyY, historyWidth, historyHeight;

    private EntityChatScreen(AnimusEntity target) {
        super(Component.literal("Animus — " + target.getName().getString()));
        this.entityId = target.getId();
        this.targetName = target.getName().getString();
    }

    public static void open(AnimusEntity entity) {
        Minecraft.getInstance().setScreen(new EntityChatScreen(entity));
    }

    private EntityAgentLoop loop() {
        return AgentLoopRegistry.getOrCreate(entityId);
    }

    @Override
    protected void init() {
        this.left = (this.width - CONTENT_WIDTH) / 2;
        this.top = (this.height - CONTENT_HEIGHT) / 2;

        int inputRowY = top + CONTENT_HEIGHT - INPUT_HEIGHT - PADDING;
        int inputWidth = CONTENT_WIDTH - SEND_WIDTH - PADDING * 3;

        this.input = new EditBox(this.font, left + PADDING, inputRowY, inputWidth, INPUT_HEIGHT,
                Component.literal("animus.chat.input"));
        this.input.setMaxLength(MAX_PROMPT_LENGTH);
        this.input.setHint(Component.literal("Talk to " + targetName + "..."));
        if (!this.savedInputText.isEmpty()) {
            this.input.setValue(this.savedInputText);
            this.savedInputText = "";
        }
        addRenderableWidget(this.input);
        setInitialFocus(this.input);

        addRenderableWidget(new SimpleButton(
                left + CONTENT_WIDTH - SEND_WIDTH - PADDING, inputRowY,
                SEND_WIDTH, INPUT_HEIGHT,
                Component.literal("Send"),
                b -> onSend()));

        // Settings button top-right.
        addRenderableWidget(new SimpleButton(
                left + CONTENT_WIDTH - SETTINGS_WIDTH, top,
                SETTINGS_WIDTH, BUTTON_HEIGHT,
                Component.literal("Settings"),
                b -> openSettings()));

        this.historyX = left + PADDING;
        this.historyY = top + BUTTON_HEIGHT + PADDING;
        this.historyWidth = CONTENT_WIDTH - PADDING * 2;
        this.historyHeight = inputRowY - this.historyY - PADDING;
    }

    private void openSettings() {
        if (this.input != null) this.savedInputText = this.input.getValue();
        Minecraft.getInstance().setScreen(new SettingsScreen(this));
    }

    private void onSend() {
        if (this.input == null) return;
        String text = this.input.getValue();
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return;
        if (!AnimusLlmClient.isConfigured()) {
            com.dwinovo.animus.Constants.LOG.warn(
                    "[animus-chat] no apiKey configured; open Settings and set it.");
            return;
        }
        loop().submitPrompt(text);
        this.input.setValue("");
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if ((keyCode == 257 || keyCode == 335) && this.input != null && this.input.isFocused()) {
            onSend();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        // Panel backdrop.
        g.fill(left, top + BUTTON_HEIGHT, left + CONTENT_WIDTH, top + CONTENT_HEIGHT, 0xA0000000);
        g.outline(left, top + BUTTON_HEIGHT, CONTENT_WIDTH, CONTENT_HEIGHT - BUTTON_HEIGHT, 0x55FFFFFF);

        // Title.
        g.centeredText(this.font, this.getTitle(), this.width / 2, top + 4, 0xFFFFFFFF);

        // History.
        Font font = this.font;
        List<RenderedLine> lines = buildLines(font);
        int totalHeight = lines.size() * LINE_HEIGHT;
        int firstLineY = totalHeight <= historyHeight
                ? historyY
                : historyY + historyHeight - totalHeight;

        g.enableScissor(historyX, historyY, historyX + historyWidth, historyY + historyHeight);
        int y = firstLineY;
        for (RenderedLine line : lines) {
            if (y + LINE_HEIGHT >= historyY) {
                g.text(font, line.text, historyX, y, line.color);
            }
            y += LINE_HEIGHT;
            if (y > historyY + historyHeight) break;
        }
        g.disableScissor();
    }

    private List<RenderedLine> buildLines(Font font) {
        ConvoState convo = loop().convo();
        List<RenderedLine> out = new ArrayList<>();
        for (ConvoState.Msg msg : convo.snapshot()) {
            switch (msg) {
                case ConvoState.Msg.User u -> appendWrapped(out, font, "▶ " + u.content(), 0xFFFFFFFF);
                case ConvoState.Msg.Assistant a -> renderAssistant(out, font, a.turn());
                case ConvoState.Msg.Tool t -> appendWrapped(out, font, "  ← " + summarise(t.content()), 0xFF808080);
            }
            if (out.size() > MAX_TOTAL_LINES) {
                int over = out.size() - MAX_TOTAL_LINES;
                for (int i = 0; i < over; i++) out.remove(0);
                out.add(0, new RenderedLine(
                        FormattedCharSequence.forward("[... older messages truncated ...]", Style.EMPTY),
                        0xFF505050));
            }
        }
        if (out.isEmpty()) {
            out.add(new RenderedLine(
                    FormattedCharSequence.forward("Say something to " + targetName + ".", Style.EMPTY),
                    0xFF606060));
        }
        return out;
    }

    private void renderAssistant(List<RenderedLine> out, Font font, AssistantTurn turn) {
        String content = turn.content();
        if (content != null && !content.isBlank()) {
            appendWrapped(out, font, "● " + content, 0xFFFFE082);
        }
        for (LlmToolCall tc : turn.toolCalls()) {
            String args = tc.arguments();
            if (args == null) args = "";
            if (args.length() > 80) args = args.substring(0, 80) + "...";
            appendWrapped(out, font, "  → " + tc.name() + "(" + args + ")", 0xFF80CBC4);
        }
    }

    private void appendWrapped(List<RenderedLine> out, Font font, String text, int color) {
        List<FormattedCharSequence> wrapped = font.split(Component.literal(text), historyWidth - 4);
        int emitted = 0;
        for (FormattedCharSequence seq : wrapped) {
            if (emitted >= MAX_LINES_PER_MESSAGE) {
                out.add(new RenderedLine(
                        FormattedCharSequence.forward("  [...truncated...]", Style.EMPTY), color));
                break;
            }
            out.add(new RenderedLine(seq, color));
            emitted++;
        }
    }

    private static String summarise(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\n', ' ').replace('\r', ' ');
        if (s.length() > 120) s = s.substring(0, 120) + "...";
        return s;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record RenderedLine(FormattedCharSequence text, int color) {}
}
