package com.dwinovo.tulpa.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A generic screen-driven dropdown (collapsed box → list on click), the data-agnostic sibling of
 * {@link ProviderDropdown}. Not a self-contained widget — the host renders it LAST (open list on top)
 * and routes clicks FIRST. Used for the model picker in the Settings tab.
 */
public final class Dropdown {

    public record Item(String id, String label) {}

    private static final int ROW = 16;
    private static final net.minecraft.resources.ResourceLocation FRAME =
            new net.minecraft.resources.ResourceLocation(com.dwinovo.tulpa.Constants.MOD_ID, "button");

    private int x, y, w, h = 18;
    private boolean open;
    private List<Item> items;
    private String selectedId;

    public Dropdown(List<Item> items, String selectedId) {
        this.items = items;
        this.selectedId = selectedId;
    }

    public void setBounds(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }
    public String selectedId() { return selectedId; }
    public boolean isOpen() { return open; }
    public void close() { open = false; }

    private String labelOf(String id) {
        for (Item it : items) if (it.id().equals(id)) return it.label();
        return id == null ? "" : id;
    }

    public void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        UiTheme th = UiTheme.current();
        GuiCompat.blitSprite(g, FRAME, x, y, w, h);
        int ty = y + (h - 8) / 2;
        Nb.text(g, font, labelOf(selectedId), x + 6, ty, th.text());
        Nb.text(g, font, open ? "▴" : "▾", x + w - 12, ty, th.textDim());

        if (open) {
            int oy = y + h - 2;
            GuiCompat.blitSprite(g, FRAME, x, oy, w, items.size() * ROW + 4);
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                int ry = oy + 2 + i * ROW;
                if (mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW) {
                    g.fill(x + 2, ry, x + w - 2, ry + ROW, 0x33000000);
                }
                Nb.text(g, font, it.label(), x + 6, ry + (ROW - 8) / 2,
                        it.id().equals(selectedId) ? th.cta() : th.text());
            }
        }
    }

    /** Returns true if consumed. The host should check {@link #selectedId()} afterwards for a change. */
    public boolean mouseClicked(double mx, double my) {
        if (open) {
            int oy = y + h;
            for (int i = 0; i < items.size(); i++) {
                int ry = oy + i * ROW;
                if (mx >= x && mx < x + w && my >= ry && my < ry + ROW) {
                    selectedId = items.get(i).id();
                    open = false;
                    return true;
                }
            }
            open = false;
            return true;
        }
        if (mx >= x && mx < x + w && my >= y && my < y + h) { open = true; return true; }
        return false;
    }
}
