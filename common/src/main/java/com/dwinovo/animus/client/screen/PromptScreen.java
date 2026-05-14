package com.dwinovo.animus.client.screen;

import com.dwinovo.animus.data.ModLanguageData;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.network.payload.AnimusPromptPayload;
import com.dwinovo.animus.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Owner-only chat GUI shown on right-click. Player types a natural-language
 * prompt; clicking Send dispatches an {@link AnimusPromptPayload} which the
 * server-side agent loop turns into LLM tool calls.
 *
 * <h2>Layout</h2>
 * Minimal — title, multi-line-ish single-row {@link EditBox}, send/cancel
 * buttons. Matches the visual language of {@link ChooseModelScreen} (same
 * pattern: vanilla buttons + title). Detailed conversation/log views can
 * land in a Phase-2 GUI; for MVP, the LLM's reply lands in the server log
 * (and eventually a chat bubble above the entity).
 *
 * <h2>Client-only class</h2>
 * Lives in {@code common} like {@link ChooseModelScreen} because
 * {@code Screen} is on the shared client jar. The dedicated server JVM
 * never loads this class because {@link com.dwinovo.animus.entity.interact.AnimusInteractHandler}
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

    @Override
    protected void init() {
        int left = (this.width - CONTENT_WIDTH) / 2;
        int top = (this.height - CONTENT_HEIGHT) / 2;

        this.input = new EditBox(this.font, left, top + 12, CONTENT_WIDTH, INPUT_HEIGHT,
                Component.translatable(ModLanguageData.Keys.GUI_PROMPT_INPUT));
        this.input.setMaxLength(AnimusPromptPayload.MAX_PROMPT_LENGTH);
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
        Services.NETWORK.sendToServer(new AnimusPromptPayload(target.getId(), text));
        this.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
