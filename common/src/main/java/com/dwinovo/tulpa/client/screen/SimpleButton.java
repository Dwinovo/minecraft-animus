package com.dwinovo.tulpa.client.screen;

import com.dwinovo.tulpa.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The Tulpa button: a vanilla GUI sprite (nine-slice, so it stretches to any width
 * with a crisp border), drawn with {@code blitSprite} like vanilla widgets — idle /
 * highlighted / disabled states from three sprites under
 * {@code textures/gui/sprites/button*.png}. Label is flat (shadowless) + coloured.
 */
public final class SimpleButton extends Button {

    private static final ResourceLocation IDLE = sprite("button");
    private static final ResourceLocation HOVER = sprite("button_highlighted");
    private static final ResourceLocation DISABLED = sprite("button_disabled");

    private static ResourceLocation sprite(String name) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name);
    }

    /** Optional centered icon sprite; when set it replaces the text label (e.g. the eye toggle). */
    private ResourceLocation icon;

    public SimpleButton(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
        super(x, y, width, height, message, onPress,
                defaultNarrationSupplier -> defaultNarrationSupplier.get());
    }

    /** Draw a centered icon sprite instead of a text label. Returns {@code this} for chaining. */
    public SimpleButton icon(ResourceLocation icon) {
        this.icon = icon;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hovered = active && isHoveredOrFocused();
        ResourceLocation sprite = !active ? DISABLED : (hovered ? HOVER : IDLE);
        g.blitSprite(sprite, x, y, w, h);

        if (icon != null) {   // icon button: a centered square sprite, no text label
            int s = Math.min(w - 4, h - 2);
            g.blitSprite(icon, x + (w - s) / 2, y + (h - s) / 2, s, s);
            return;
        }

        Font font = Minecraft.getInstance().font;
        int color = active ? UiTheme.current().text() : 0xFF6E5E48;
        int tw = font.width(getMessage());
        Nb.text(g, font, getMessage(), x + (w - tw) / 2, y + (h - 8) / 2, color);   // flat, coloured
    }
}
