package com.dwinovo.animus.client.screen;

import com.dwinovo.animus.agent.llm.AnimusLlmClient;
import com.dwinovo.animus.agent.llm.ConvoState;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.LlmToolCall;
import com.dwinovo.animus.anim.api.ModelLibrary;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.agent.ClientAnimusLookup;
import com.dwinovo.animus.client.agent.EntityAgentLoop;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.network.payload.OpenAnimusInventoryPayload;
import com.dwinovo.animus.network.payload.SetModelPayload;
import com.dwinovo.animus.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Owner-facing GUI bound to one tamed Animus. Opened on owner right-click.
 *
 * <h2>Views</h2>
 * Top button row switches between Chat and Model; the Inventory button opens a
 * vanilla chest menu over this Animus's own inventory; Settings opens config.
 * Each Animus carries its own {@link EntityAgentLoop} conversation — the chat
 * runs the LLM with the owner's API key and dispatches tool calls to the body.
 *
 * <h2>Client-only</h2>
 * Lives in {@code common} because {@code Screen} is on the shared client jar;
 * the dedicated server never loads it ({@code AnimusInteractHandler} guards the
 * open call with {@code isClientSide()}).
 */
public final class EntityChatScreen extends Screen {

    private enum View { CHAT, MODEL }

    private static final int CONTENT_WIDTH = 320;
    private static final int CONTENT_HEIGHT = 220;
    private static final int TOP_BAR = 20;
    private static final int INPUT_HEIGHT = 18;
    private static final int PADDING = 6;
    private static final int LINE_HEIGHT = 10;
    private static final int MAX_PROMPT_LENGTH = 1024;
    private static final int MAX_LINES_PER_MESSAGE = 8;
    private static final int MAX_TOTAL_LINES = 200;
    private static final int MODEL_ROW_HEIGHT = 14;

    /** Height of the vitals strip (HP bar + equipment slots) atop the chat body. */
    private static final int STATUS_HEIGHT = 24;
    /** Equipment shown left→right in the strip. */
    private static final EquipmentSlot[] EQUIPMENT_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
    };

    private final UUID entityUuid;
    private final String targetName;

    private View view = View.CHAT;
    private EditBox input;
    private SimpleButton stopButton;
    private SimpleButton compactButton;
    private String savedInputText = "";

    private int left;
    private int top;
    private int bodyX;
    private int bodyY;
    private int bodyWidth;
    private int bodyHeight;
    /** Top of the vitals strip (CHAT view only; chat lines start below it). */
    private int statusY;

    private EntityChatScreen(UUID entityUuid, String targetName) {
        super(Component.literal("Animus - " + targetName));
        this.entityUuid = entityUuid;
        this.targetName = targetName;
    }

    /** Physical entry point: owner right-clicked the body. Enrolls it in the roster. */
    public static void open(AnimusEntity entity) {
        com.dwinovo.animus.client.agent.AnimusRoster.instance()
                .record(entity.getUUID(), entity.getName().getString());
        Minecraft.getInstance().setScreen(
                new EntityChatScreen(entity.getUUID(), entity.getName().getString()));
    }

    /**
     * Remote entry point (roster panel / hotkey): no entity instance needed —
     * everything in this screen resolves the body lazily by UUID and tolerates
     * it being unloaded, so chat, Stop, and Compact all work at any distance.
     */
    public static void openRemote(UUID entityUuid, String name) {
        Minecraft.getInstance().setScreen(new EntityChatScreen(entityUuid, name));
    }

    private EntityAgentLoop loop() {
        return AgentLoopRegistry.getOrCreate(entityUuid);
    }

    private AnimusEntity resolveEntity() {
        return ClientAnimusLookup.resolve(entityUuid);
    }

    @Override
    protected void init() {
        this.left = (this.width - CONTENT_WIDTH) / 2;
        this.top = (this.height - CONTENT_HEIGHT) / 2;

        this.bodyX = left + PADDING;
        this.bodyY = top + TOP_BAR + PADDING;
        this.bodyWidth = CONTENT_WIDTH - PADDING * 2;
        this.bodyHeight = CONTENT_HEIGHT - TOP_BAR - PADDING * 2;

        buildTopBar();
        if (view == View.CHAT) {
            buildChatView();
        }
    }

    private void buildTopBar() {
        int btnW = 56;
        int x = left;
        SimpleButton chat = new SimpleButton(x, top, btnW, TOP_BAR - 2,
                Component.literal("Chat"), b -> switchView(View.CHAT));
        if (view == View.CHAT) chat.active = false;
        addRenderableWidget(chat);
        x += btnW + 2;

        SimpleButton model = new SimpleButton(x, top, btnW, TOP_BAR - 2,
                Component.literal("Model"), b -> switchView(View.MODEL));
        if (view == View.MODEL) model.active = false;
        addRenderableWidget(model);
        x += btnW + 2;

        addRenderableWidget(new SimpleButton(x, top, btnW, TOP_BAR - 2,
                Component.literal("Inventory"), b -> openInventory()));
        x += btnW + 2;

        // Manual context compaction: summarize the whole history into one
        // message. Enabled state refreshed each frame (idle + enough history).
        this.compactButton = new SimpleButton(x, top, btnW, TOP_BAR - 2,
                Component.literal("Compact"), b -> loop().requestCompact());
        this.compactButton.active = loop().canCompact();
        addRenderableWidget(this.compactButton);

        int settingsW = 56;
        addRenderableWidget(new SimpleButton(left + CONTENT_WIDTH - settingsW, top,
                settingsW, TOP_BAR - 2,
                Component.literal("Settings"), b -> openSettings()));
    }

    private void buildChatView() {
        // Reserve the vitals strip (HP + equipment) above the chat lines.
        this.statusY = this.bodyY;
        this.bodyY += STATUS_HEIGHT;

        int inputRowY = top + CONTENT_HEIGHT - INPUT_HEIGHT - PADDING;
        int sendW = 44;
        int stopW = 44;
        int inputWidth = CONTENT_WIDTH - sendW - stopW - PADDING * 4;

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

        // Stop: owner-triggered interrupt. Enabled state is refreshed each frame
        // in extractRenderState from loop().canInterrupt().
        this.stopButton = new SimpleButton(
                left + PADDING + inputWidth + PADDING, inputRowY, stopW, INPUT_HEIGHT,
                Component.literal("Stop"), b -> loop().abort());
        this.stopButton.active = loop().canInterrupt();
        addRenderableWidget(this.stopButton);

        addRenderableWidget(new SimpleButton(
                left + CONTENT_WIDTH - sendW - PADDING, inputRowY, sendW, INPUT_HEIGHT,
                Component.literal("Send"), b -> onSend()));

        this.bodyHeight = inputRowY - this.bodyY - PADDING;
    }

    private void switchView(View v) {
        if (this.input != null) {
            this.savedInputText = this.input.getValue();
        }
        this.view = v;
        this.input = null;
        this.stopButton = null;
        this.compactButton = null;
        rebuildWidgets();
    }

    private void openSettings() {
        if (this.input != null) {
            this.savedInputText = this.input.getValue();
        }
        Minecraft.getInstance().setScreen(new SettingsScreen(this));
    }

    private void openInventory() {
        // GUI request/response is momentary and same-dimension (the body is in
        // view), so the volatile network int id is valid here — resolve it live.
        AnimusEntity entity = resolveEntity();
        if (entity != null) {
            Services.NETWORK.sendToServer(new OpenAnimusInventoryPayload(entity.getId()));
        }
    }

    private void onSend() {
        if (this.input == null) {
            return;
        }
        String text = this.input.getValue();
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) {
            return;
        }
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
    public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean doubleClick) {
        if (view == View.MODEL && mouseButtonEvent.button() == 0) {
            double mouseX = mouseButtonEvent.x();
            double mouseY = mouseButtonEvent.y();
            List<Identifier> models = sortedModels();
            for (int i = 0; i < models.size(); i++) {
                int rowY = bodyY + i * MODEL_ROW_HEIGHT;
                if (mouseX >= bodyX && mouseX <= bodyX + bodyWidth
                        && mouseY >= rowY && mouseY < rowY + MODEL_ROW_HEIGHT) {
                    AnimusEntity entity = resolveEntity();
                    if (entity != null) {
                        Services.NETWORK.sendToServer(new SetModelPayload(entity.getId(), models.get(i)));
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseButtonEvent, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        g.fill(left, top + TOP_BAR, left + CONTENT_WIDTH, top + CONTENT_HEIGHT, 0xA0000000);
        g.outline(left, top + TOP_BAR, CONTENT_WIDTH, CONTENT_HEIGHT - TOP_BAR, 0x55FFFFFF);

        if (this.compactButton != null) {
            this.compactButton.active = loop().canCompact();
        }
        if (view == View.CHAT) {
            // Stop is only actionable while a turn runs or prompts are queued.
            if (this.stopButton != null) {
                this.stopButton.active = loop().canInterrupt();
            }
            renderStatusStrip(g, mouseX, mouseY);
            renderChat(g);
        } else {
            renderModelList(g, mouseX, mouseY);
        }
    }

    /**
     * Vitals strip: HP bar (left) + the six equipment slots (right), read live
     * off the client entity — health and equipment are vanilla-synced to
     * tracking clients, so no extra networking is needed. Hovering a slot shows
     * the vanilla item tooltip.
     */
    private void renderStatusStrip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        AnimusEntity entity = resolveEntity();
        if (entity == null) {
            g.text(font, Component.literal("(body out of view)"), bodyX, statusY + 5, 0xFF606060);
            return;
        }

        float hp = entity.getHealth();
        float max = entity.getMaxHealth();
        g.text(font, Component.literal("HP " + fmtHp(hp) + "/" + fmtHp(max)),
                bodyX, statusY, 0xFFFF6E6E);
        int barW = 70;
        int barH = 5;
        int barY = statusY + 11;
        g.fill(bodyX, barY, bodyX + barW, barY + barH, 0xFF3A1010);
        int fill = (int) (barW * Math.clamp(hp / max, 0.0f, 1.0f));
        if (fill > 0) {
            g.fill(bodyX, barY, bodyX + fill, barY + barH, 0xFFE53935);
        }
        g.outline(bodyX - 1, barY - 1, barW + 2, barH + 2, 0x55FFFFFF);

        int slotStep = 18;
        int x = bodyX + bodyWidth - EQUIPMENT_SLOTS.length * slotStep + 1;
        int y = statusY + 1;
        for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
            g.fill(x, y, x + 16, y + 16, 0x40000000);
            g.outline(x, y, 16, 16, 0x33FFFFFF);
            ItemStack stack = entity.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                g.item(stack, x, y);
                g.itemDecorations(font, stack, x, y);
                if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                    g.setTooltipForNextFrame(font, stack, mouseX, mouseY);
                }
            }
            x += slotStep;
        }
    }

    private static String fmtHp(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v);
    }

    private void renderChat(GuiGraphicsExtractor g) {
        Font font = this.font;
        List<RenderedLine> lines = buildChatLines(font);
        int totalHeight = lines.size() * LINE_HEIGHT;
        int firstLineY = totalHeight <= bodyHeight ? bodyY : bodyY + bodyHeight - totalHeight;

        g.enableScissor(bodyX, bodyY, bodyX + bodyWidth, bodyY + bodyHeight);
        int y = firstLineY;
        for (RenderedLine line : lines) {
            if (y + LINE_HEIGHT >= bodyY) {
                g.text(font, line.text, bodyX, y, line.color);
            }
            y += LINE_HEIGHT;
            if (y > bodyY + bodyHeight) {
                break;
            }
        }
        g.disableScissor();
    }

    private void renderModelList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = this.font;
        AnimusEntity entity = resolveEntity();
        Identifier current = entity == null ? null : entity.getModelKey();
        List<Identifier> models = sortedModels();
        if (models.isEmpty()) {
            g.text(font, Component.literal("No models installed."), bodyX, bodyY, 0xFF888888);
            return;
        }
        for (int i = 0; i < models.size(); i++) {
            Identifier id = models.get(i);
            int rowY = bodyY + i * MODEL_ROW_HEIGHT;
            if (rowY + MODEL_ROW_HEIGHT > bodyY + bodyHeight) {
                break;
            }
            boolean hovered = mouseX >= bodyX && mouseX <= bodyX + bodyWidth
                    && mouseY >= rowY && mouseY < rowY + MODEL_ROW_HEIGHT;
            boolean selected = id.equals(current);
            int color = selected ? 0xFF80CBC4 : (hovered ? 0xFFFFFFFF : 0xFFBBBBBB);
            String prefix = selected ? "* " : "  ";
            g.text(font, Component.literal(prefix + id), bodyX + 2, rowY + 2, color);
        }
    }

    private static List<Identifier> sortedModels() {
        List<Identifier> out = new ArrayList<>(ModelLibrary.keys());
        out.sort((a, b) -> a.toString().compareTo(b.toString()));
        return out;
    }

    private List<RenderedLine> buildChatLines(Font font) {
        ConvoState convo = loop().convo();
        List<RenderedLine> out = new ArrayList<>();
        for (ConvoState.Msg msg : convo.snapshot()) {
            switch (msg) {
                case ConvoState.Msg.User u -> appendWrapped(out, font, "> " + u.content(), 0xFFFFFFFF);
                case ConvoState.Msg.Assistant a -> renderAssistant(out, font, a.turn());
                case ConvoState.Msg.Tool t -> appendWrapped(out, font, "  < " + summarise(t.content()), 0xFF808080);
            }
            if (out.size() > MAX_TOTAL_LINES) {
                int over = out.size() - MAX_TOTAL_LINES;
                for (int i = 0; i < over; i++) {
                    out.remove(0);
                }
            }
        }
        if (loop().isCompacting()) {
            out.add(new RenderedLine(
                    FormattedCharSequence.forward("正在压缩对话历史...", Style.EMPTY),
                    0xFF808080));
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
            appendWrapped(out, font, "* " + content, 0xFFFFE082);
        }
        for (LlmToolCall tc : turn.toolCalls()) {
            String args = tc.arguments();
            if (args == null) args = "";
            if (args.length() > 80) args = args.substring(0, 80) + "...";
            appendWrapped(out, font, "  -> " + tc.name() + "(" + args + ")", 0xFF80CBC4);
        }
    }

    private void appendWrapped(List<RenderedLine> out, Font font, String text, int color) {
        List<FormattedCharSequence> wrapped = font.split(Component.literal(text), bodyWidth - 4);
        int emitted = 0;
        for (FormattedCharSequence seq : wrapped) {
            if (emitted >= MAX_LINES_PER_MESSAGE) {
                out.add(new RenderedLine(
                        FormattedCharSequence.forward("  [...]", Style.EMPTY), color));
                break;
            }
            out.add(new RenderedLine(seq, color));
            emitted++;
        }
    }

    private static String summarise(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace('\n', ' ').replace('\r', ' ');
        if (s.length() > 120) {
            s = s.substring(0, 120) + "...";
        }
        return s;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record RenderedLine(FormattedCharSequence text, int color) {}
}
