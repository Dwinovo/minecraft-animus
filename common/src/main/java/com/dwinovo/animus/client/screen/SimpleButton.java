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

    public SimpleButton(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
        super(x, y, width, height, message, onPress,
                defaultNarrationSupplier -> defaultNarrationSupplier.get());
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // BlockFrame button: warm parchment fill + thick warm-brown border + hard offset shadow
        // (the filled look marks it CLICKABLE). Disabled / pressed flattens the shadow.
        UiTheme t = UiTheme.current();
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hovered = active && isHoveredOrFocused();
        int border = t.border();
        int fill = !active ? 0xFFB6A988 : (hovered ? 0xFFF2E4C2 : 0xFFE7D7B2);

        int sh = active ? (hovered ? 2 : 3) : 0;
        int oy = active && hovered ? 1 : 0;             // nudge down on hover (button "press")
        if (sh > 0) graphics.fill(x + sh, y + sh, x + w + sh, y + h + sh, border);
        graphics.fill(x, y + oy, x + w, y + h + oy, fill);
        thickBorder(graphics, x, y + oy, w, h, 2, border);
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        graphics.centeredText(font, getMessage(), x + w / 2, y + oy + (h - 8) / 2,
                active ? t.text() : 0xFF6E5E48);
    }

    /** Square thick border = four filled edge rects (no rounded corners). */
    static void thickBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int t, int color) {
        g.fill(x, y, x + w, y + t, color);
        g.fill(x, y + h - t, x + w, y + h, color);
        g.fill(x, y, x + t, y + h, color);
        g.fill(x + w - t, y, x + w, y + h, color);
    }
}
