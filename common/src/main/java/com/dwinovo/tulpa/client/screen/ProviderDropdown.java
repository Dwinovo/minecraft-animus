package com.dwinovo.tulpa.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A screen-driven provider picker: a collapsed box showing the current provider, expanding to the live
 * provider list on click (+ an optional "add site" row). Not a self-contained widget — the host renders
 * it LAST (open list on top) and routes clicks FIRST. Shared by {@link SettingsScreen} and the
 * {@link TulpaScreen} Settings tab.
 */
public final class ProviderDropdown {

    /** Sentinel selection id for the "+ add a site" row (only present when {@code allowAddSite}). */
    public static final String ADD_SITE = "__add_site__";

    private static final int ROW = 16;
    private static final net.minecraft.resources.ResourceLocation FRAME =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.dwinovo.tulpa.Constants.MOD_ID, "button");

    private final List<LlmProviders.Option> options;   // live snapshot at construction (rebuilt each settings build)
    private final boolean allowAddSite;
    private int x, y, w, h = 18;
    private boolean open;
    private String selectedId;

    public ProviderDropdown(String selectedId, boolean allowAddSite) {
        this.options = LlmProviders.all();
        this.allowAddSite = allowAddSite;
        this.selectedId = ADD_SITE.equals(selectedId) ? selectedId : LlmProviders.normalize(selectedId);
    }

    public void setBounds(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }
    public String selectedId() { return selectedId; }
    public boolean isOpen() { return open; }
    public void close() { open = false; }

    private int rowCount() { return options.size() + (allowAddSite ? 1 : 0); }

    private String label(int i) {
        if (i < options.size()) return options.get(i).displayName();
        return "+ 添加站点";
    }
    private String idAt(int i) { return i < options.size() ? options.get(i).id() : ADD_SITE; }

    private String selectedLabel() {
        if (ADD_SITE.equals(selectedId)) return "+ 添加站点";
        for (LlmProviders.Option o : options) if (o.id().equals(selectedId)) return o.displayName();
        return options.isEmpty() ? selectedId : options.get(0).displayName();
    }

    public void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        UiTheme th = UiTheme.current();
        java.util.function.Function<net.minecraft.resources.ResourceLocation, net.minecraft.client.renderer.RenderType> pipe = net.minecraft.client.renderer.RenderType::guiTextured;
        g.blitSprite(pipe, FRAME, x, y, w, h);
        int ty = y + (h - 8) / 2;
        Nb.text(g, font, selectedLabel(), x + 6, ty, th.text());
        Nb.text(g, font, open ? "▴" : "▾", x + w - 12, ty, th.textDim());

        if (open) {
            int oy = y + h - 2;
            int n = rowCount();
            g.blitSprite(pipe, FRAME, x, oy, w, n * ROW + 4);
            for (int i = 0; i < n; i++) {
                int ry = oy + 2 + i * ROW;
                if (mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW) {
                    g.fill(x + 2, ry, x + w - 2, ry + ROW, 0x33000000);
                }
                boolean add = i >= options.size();
                int color = idAt(i).equals(selectedId) ? th.cta() : (add ? th.run() : th.text());
                Nb.text(g, font, label(i), x + 6, ry + (ROW - 8) / 2, color);
            }
        }
    }

    /** Returns true if consumed. The host checks {@link #selectedId()} (== {@link #ADD_SITE} → add flow). */
    public boolean mouseClicked(double mx, double my) {
        if (open) {
            int oy = y + h;
            for (int i = 0; i < rowCount(); i++) {
                int ry = oy + i * ROW;
                if (mx >= x && mx < x + w && my >= ry && my < ry + ROW) {
                    selectedId = idAt(i);
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
