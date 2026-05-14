package com.dwinovo.animus.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Vanilla {@link Button} subclass with a flat, half-transparent black skin
 * — used throughout the Animus GUI so the busier Minecraft beveled-button
 * style doesn't clash with the dark preview backdrop.
 *
 * <h2>States</h2>
 * <ul>
 *   <li><b>Idle</b>: 54%-opaque black fill, faint white border</li>
 *   <li><b>Hover</b>: slightly brighter fill, full-opacity warm-white
 *       border, top edge highlight</li>
 *   <li><b>Disabled</b> (also used to mark the currently-selected entry):
 *       30%-opaque black fill, muted border</li>
 * </ul>
 *
 * <p>Pattern lifted from the music-box screen in {@code minecraft-chiikawa}
 * (same author) — staying consistent across both projects' GUIs.
 */
public final class SimpleButton extends Button {

    private static final int BG_IDLE       = 0x8A000000;
    private static final int BG_HOVERED    = 0xB0202020;
    private static final int BG_DISABLED   = 0x52000000;
    private static final int BORDER_IDLE     = 0x77FFFFFF;
    private static final int BORDER_HOVERED  = 0xFFE8E1D2;
    private static final int BORDER_DISABLED = 0x33FFFFFF;
    private static final int HIGHLIGHT     = 0x33FFFFFF;

    public SimpleButton(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
        super(x, y, width, height, message, onPress,
                defaultNarrationSupplier -> defaultNarrationSupplier.get());
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int width = getWidth();
        int height = getHeight();
        boolean hovered = active && isHoveredOrFocused();

        int bg     = active ? (hovered ? BG_HOVERED     : BG_IDLE)     : BG_DISABLED;
        int border = active ? (hovered ? BORDER_HOVERED : BORDER_IDLE) : BORDER_DISABLED;

        graphics.fill(x, y, x + width, y + height, bg);
        graphics.outline(x, y, width, height, border);
        if (hovered) {
            graphics.fill(x + 1, y + 1, x + width - 1, y + 2, HIGHLIGHT);
        }
        extractDefaultLabel(graphics.textRendererForWidget(this,
                GuiGraphicsExtractor.HoveredTextEffects.NONE));
    }
}
