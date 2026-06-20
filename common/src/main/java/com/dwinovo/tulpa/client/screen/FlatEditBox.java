package com.dwinovo.tulpa.client.screen;

import com.dwinovo.tulpa.Constants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.function.BiFunction;

/**
 * A borderless {@link EditBox} that paints its text WITHOUT the vanilla drop shadow,
 * and draws the caret as a thin fill bar instead of the shadowed {@code "_"}, to match
 * the flat Cottage UI.
 *
 * <p>Vanilla hardcodes the shadow in {@code renderWidget} and (before 1.21.6) exposes
 * no toggle; {@code Style.withShadowColor} is 1.21.5-only — so neither lever is portable
 * to older targets. Rather than a fragile {@code @Redirect} mixin or access-wideners,
 * this REUSES all of EditBox's input/state machinery and only re-does the paint, through
 * PUBLIC getters ({@link #getValue()} / {@link #getCursorPosition()} / {@link #getInnerWidth()}
 * / {@link #isFocused()} / geometry). The horizontal scroll offset and blink phase — the
 * only state without getters — are tracked here. Geometry matches vanilla's borderless
 * branch exactly: text at {@code (getX(), getY())}. Assumes {@code setBordered(false)}.
 */
public class FlatEditBox extends EditBox {

    /** Cottage-style caret sprite (brown-capped amber bar, HyperFrames pixel art, native 3x10). */
    private static final ResourceLocation CARET = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "caret");
    private static final int CARET_W = 3, CARET_H = 10;
    private static final int SELECT_COLOR = 0x804E7480;   // translucent reply-teal
    private static final long BLINK_MS = 300L;

    private final Font font;
    private int color = 0xE0E0E0;                          // captured from setTextColor
    private Component hint;                                // captured from setHint
    private BiFunction<String, Integer, FormattedCharSequence> fmt =
            (s, off) -> FormattedCharSequence.forward(s, Style.EMPTY);
    private long focusTime;
    private int scroll;                                    // our own displayPos (left scroll offset)

    public FlatEditBox(Font font, int x, int y, int w, int h, Component msg) {
        super(font, x, y, w, h, msg);
        this.font = font;
    }

    @Override public void setTextColor(int c) { super.setTextColor(c); this.color = c; }
    @Override public void setHint(Component c) { super.setHint(c); this.hint = c; }
    @Override public void setFormatter(BiFunction<String, Integer, FormattedCharSequence> f) {
        super.setFormatter(f);
        this.fmt = f;
    }
    @Override public void setFocused(boolean f) {
        super.setFocused(f);
        if (f) this.focusTime = System.currentTimeMillis();
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (!isVisible()) return;

        String value = getValue();
        int innerW = getInnerWidth();
        int textX = getX();                               // borderless: no +4 inset
        int textY = getY();                               // borderless: top-aligned (matches vanilla)
        int cursor = getCursorPosition();

        // Empty + unfocused: the box's own hint (the mod usually draws its own placeholder
        // via Nb instead, in which case no hint is set and nothing is drawn here).
        if (value.isEmpty() && !isFocused()) {
            if (hint != null) g.drawString(font, hint, textX, textY, color, false);
            return;
        }

        // Keep the caret inside the visible window — our stand-in for vanilla's private
        // displayPos, recomputed/persisted so the text scrolls but doesn't jump.
        scroll = Mth.clamp(scroll, 0, value.length());
        if (cursor < scroll) scroll = cursor;
        while (scroll < cursor && font.width(value.substring(scroll, cursor)) > innerW) scroll++;
        String visible = font.plainSubstrByWidth(value.substring(scroll), innerW);
        int visEnd = scroll + visible.length();

        // Selection box — highlightPos is private, so derive the range from getHighlighted().
        String hl = getHighlighted();
        if (!hl.isEmpty()) {
            int len = hl.length();
            int a, b;
            if (cursor - len >= 0 && value.substring(cursor - len, cursor).equals(hl)) { a = cursor - len; b = cursor; }
            else { a = cursor; b = Math.min(value.length(), cursor + len); }
            int va = Mth.clamp(a, scroll, visEnd), vb = Mth.clamp(b, scroll, visEnd);
            if (vb > va) {
                int hx1 = textX + font.width(value.substring(scroll, va));
                int hx2 = textX + font.width(value.substring(scroll, vb));
                g.fill(hx1, textY - 1, hx2, textY + 9, SELECT_COLOR);
            }
        }

        // The text, through the box's formatter, drawn flat (dropShadow = false).
        if (!visible.isEmpty()) {
            g.drawString(font, fmt.apply(visible, scroll), textX, textY, color, false);
        }

        // Caret: the Cottage pixel-art bar sprite (no "_" glyph → no shadow), blinking on focus,
        // centred on the cursor column and blitted at its native 3x10 (crisp, no scaling).
        boolean blink = isFocused() && ((System.currentTimeMillis() - focusTime) / BLINK_MS) % 2 == 0;
        if (blink && cursor >= scroll && cursor <= visEnd) {
            int cx = textX + font.width(value.substring(scroll, cursor));
            g.blitSprite(RenderType::guiTextured, CARET, cx - 1, textY - 1, CARET_W, CARET_H);
        }
    }
}
