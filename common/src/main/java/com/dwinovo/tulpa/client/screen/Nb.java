package com.dwinovo.tulpa.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

/**
 * Shared BlockFrame ("neobrutalist") drawing helpers for the Tulpa GUI.
 *
 * <p>Text is always drawn FLAT (no drop shadow) with the colour baked into the
 * {@link net.minecraft.network.chat.Style} — on this build the shadowless text path
 * ignores the colour param (renders black), and a shadow on dark text over a light
 * ground smudges. Baking into the Style gives crisp, coloured, flat text.
 */
public final class Nb {

    private Nb() {}

    /** A flat, coloured text Component (colour in the Style). */
    public static Component colored(String s, int color) {
        return Component.literal(s).withStyle(st -> st.withColor(TextColor.fromRgb(color & 0xFFFFFF)));
    }

    public static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
        g.drawString(font, colored(s, color), x, y, -1, false);
    }

    public static void text(GuiGraphics g, Font font, Component c, int x, int y, int color) {
        g.drawString(font, c.copy().withStyle(st -> st.withColor(TextColor.fromRgb(color & 0xFFFFFF))), x, y, -1, false);
    }

    /** Transparent shadow colour — the per-glyph {@code shadow_color} text feature (1.21.5+).
     *  EditBox always draws its text WITH shadow (no setTextShadow before 1.21.6), but an
     *  alpha-0 shadow colour renders nothing, so the box reads flat. */
    public static final Style FLAT = Style.EMPTY.withShadowColor(0);

    /** Make an EditBox's text shadowless via its formatter (the box keeps its text colour
     *  from {@code setTextColor}; only the shadow goes transparent). No setTextShadow on 1.21.5. */
    public static void noTextShadow(EditBox e) {
        e.setFormatter((s, offset) -> FormattedCharSequence.forward(s, FLAT));
    }

    /** Square thick border = four filled edge rects (no rounded corners). */
    public static void border(GuiGraphics g, int x, int y, int w, int h, int t, int color) {
        g.fill(x, y, x + w, y + t, color);
        g.fill(x, y + h - t, x + w, y + h, color);
        g.fill(x, y, x + t, y + h, color);
        g.fill(x + w - t, y, x + w, y + h, color);
    }
}
