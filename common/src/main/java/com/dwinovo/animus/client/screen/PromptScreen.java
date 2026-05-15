package com.dwinovo.animus.client.screen;

import com.dwinovo.animus.agent.llm.AnimusLlmClient;
import com.dwinovo.animus.client.agent.ClientAgentLoopRegistry;
import com.dwinovo.animus.data.ModLanguageData;
import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Owner-only chat GUI shown on right-click. Player types a natural-language
 * prompt; clicking Send hands it to the client-side
 * {@link com.dwinovo.animus.client.agent.ClientAgentLoop} for this entity,
 * which runs the LLM with the player's own API key and dispatches resulting
 * tool calls to the server via {@code ExecuteToolPayload}.
 *
 * <h2>Why no packet here</h2>
 * The LLM moved from server-side to client-side so per-player token billing
 * works (each player's API key drives their own conversations). The
 * server never sees the prompt — it only receives validated tool-call
 * dispatches one level deeper, after the client's LLM has decided what to do.
 *
 * <h2>Layout</h2>
 * Minimal — title, single-row {@link EditBox}, send/cancel buttons. If the
 * client config has no API key set, the input field still works (so the user
 * can preview the GUI) but Send no-ops and the agent loop logs a warning.
 * Surfacing the empty-key state in the GUI itself is Phase-2 polish.
 *
 * <h2>Client-only class</h2>
 * Lives in {@code common} like {@link ChooseModelScreen} because
 * {@code Screen} is on the shared client jar. The dedicated server JVM
 * never loads this class — {@link com.dwinovo.animus.entity.interact.AnimusInteractHandler}
 * guards the {@link #open} call with {@code level().isClientSide()}.
 */
public final class PromptScreen extends Screen {

    private static final int CONTENT_WIDTH = 240;
    private static final int CONTENT_HEIGHT = 80;
    private static final int INPUT_HEIGHT = 20;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 60;
    private static final int BUTTON_GAP = 4;

    private final AnimusEntity target;
    private EditBox input;

    public PromptScreen(AnimusEntity target) {
        super(Component.translatable(ModLanguageData.Keys.GUI_PROMPT_TITLE));
        this.target = target;
    }

    public static void open(AnimusEntity entity) {
        Minecraft.getInstance().setScreen(new PromptScreen(entity));
    }

    /** Soft cap matches the server's defence-in-depth limit on tool argument JSON length. */
    private static final int MAX_PROMPT_LENGTH = 1024;

    @Override
    protected void init() {
        int left = (this.width - CONTENT_WIDTH) / 2;
        int top = (this.height - CONTENT_HEIGHT) / 2;

        this.input = new EditBox(this.font, left, top + 12, CONTENT_WIDTH, INPUT_HEIGHT,
                Component.translatable(ModLanguageData.Keys.GUI_PROMPT_INPUT));
        this.input.setMaxLength(MAX_PROMPT_LENGTH);
        this.input.setHint(Component.translatable(ModLanguageData.Keys.GUI_PROMPT_HINT));
        addRenderableWidget(this.input);
        setInitialFocus(this.input);

        int footerY = top + CONTENT_HEIGHT - BUTTON_HEIGHT;
        int rightX = left + CONTENT_WIDTH - BUTTON_WIDTH;
        int leftX  = rightX - BUTTON_WIDTH - BUTTON_GAP;

        addRenderableWidget(new SimpleButton(leftX, footerY, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable(ModLanguageData.Keys.GUI_PROMPT_CANCEL),
                b -> this.onClose()));
        addRenderableWidget(new SimpleButton(rightX, footerY, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable(ModLanguageData.Keys.GUI_PROMPT_SEND),
                this::onSend));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int top = (this.height - CONTENT_HEIGHT) / 2;
        graphics.centeredText(this.font, this.getTitle(),
                this.width / 2, top - 14, 0xFFFFFFFF);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Enter sends, like a vanilla chat input. KeyEvent.key() carries the
        // GLFW key code; 257 = Enter, 335 = numpad Enter.
        int keyCode = event.key();
        if ((keyCode == 257 || keyCode == 335)
                && this.input != null && this.input.isFocused()) {
            onSend(null);
            return true;
        }
        return super.keyPressed(event);
    }

    private void onSend(Button btn) {
        if (this.input == null) return;
        String text = this.input.getValue();
        if (text == null) text = "";
        text = text.trim();
        if (text.isEmpty()) {
            this.onClose();
            return;
        }
        if (!AnimusLlmClient.isConfigured()) {
            // Don't crash, don't send — agent loop will also log this.
            // GUI-side surfacing is Phase-2; for now we log and close.
            com.dwinovo.animus.Constants.LOG.warn(
                    "[animus-prompt] no apiKey configured; ignoring prompt. Edit config/animus.json (Fabric) or animus-common.toml (NeoForge) and restart.");
            this.onClose();
            return;
        }
        ClientAgentLoopRegistry.getOrCreate(target.getId()).submitPrompt(text);
        this.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
