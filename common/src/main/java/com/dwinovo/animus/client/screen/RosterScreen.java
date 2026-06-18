package com.dwinovo.animus.client.screen;

import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.agent.AnimusRoster;
import com.dwinovo.animus.client.agent.ClientAnimusLookup;
import com.dwinovo.animus.client.data.ClientAnimusLocations;
import com.dwinovo.animus.network.payload.LocateAnimusPayload;
import com.dwinovo.animus.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The companion control panel, opened by hotkey: one row per known pet
 * (see {@link AnimusRoster}), click a row to open its chat — no need to
 * stand next to the body, or even share a dimension with it.
 *
 * <h2>Two data layers per row</h2>
 * <ul>
 *   <li><b>Free, instant</b>: the agent loop's busy/queued state (pure client
 *       memory) and live HP/distance when the entity happens to be in client
 *       view.</li>
 *   <li><b>One round-trip</b>: for out-of-view pets a {@link LocateAnimusPayload}
 *       batch request fetches position/dimension/HP; re-sent every
 *       {@link #LOCATE_REFRESH_TICKS} while the panel is open.</li>
 * </ul>
 */
public final class RosterScreen extends Screen {

    private static final int CONTENT_WIDTH = 320;
    private static final int CONTENT_HEIGHT = 220;
    private static final int TOP_BAR = 20;
    private static final int PADDING = 6;
    private static final int ROW_HEIGHT = 26;
    /** Re-request server locations every 2 seconds while open. */
    private static final int LOCATE_REFRESH_TICKS = 40;
    /** A server snapshot older than this is shown but flagged stale. */
    private static final long SNAPSHOT_STALE_MS = 15_000;

    private List<AnimusRoster.Entry> roster = List.of();
    private int left;
    private int top;
    private int bodyX;
    private int bodyY;
    private int bodyWidth;
    private int tickCounter = 0;

    private RosterScreen() {
        super(Component.literal("Companions"));
    }

    /**
     * Hotkey entry point: zero pets → panel with the empty-state hint;
     * exactly one → straight into its chat (no pointless picker); more → panel.
     */
    public static void openHotkey() {
        AnimusRoster roster = AnimusRoster.instance();
        if (roster.size() == 1) {
            AnimusRoster.Entry only = roster.entries().get(0);
            AnimusScreen.open(only.uuid(), only.name());
            return;
        }
        Minecraft.getInstance().setScreen(new RosterScreen());
    }

    @Override
    protected void init() {
        this.left = (this.width - CONTENT_WIDTH) / 2;
        this.top = (this.height - CONTENT_HEIGHT) / 2;
        this.bodyX = left + PADDING;
        this.bodyY = top + TOP_BAR + PADDING;
        this.bodyWidth = CONTENT_WIDTH - PADDING * 2;
        this.roster = AnimusRoster.instance().entries();
        requestLocations();
    }

    @Override
    public void tick() {
        if (++tickCounter % LOCATE_REFRESH_TICKS == 0) {
            requestLocations();
        }
    }

    private void requestLocations() {
        if (roster.isEmpty() || Minecraft.getInstance().getConnection() == null) return;
        List<UUID> uuids = new ArrayList<>(Math.min(roster.size(), LocateAnimusPayload.MAX_UUIDS));
        for (AnimusRoster.Entry e : roster) {
            if (uuids.size() >= LocateAnimusPayload.MAX_UUIDS) break;
            uuids.add(e.uuid());
        }
        Services.NETWORK.sendToServer(new LocateAnimusPayload(uuids));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);

        g.fill(left, top, left + CONTENT_WIDTH, top + CONTENT_HEIGHT, 0xA0000000);
        g.outline(left, top, CONTENT_WIDTH, CONTENT_HEIGHT, 0x55FFFFFF);
        g.text(font, Component.literal("Companions"), bodyX, top + 6, 0xFFFFFFFF);

        if (roster.isEmpty()) {
            g.text(font, Component.literal("No companions in this world."), bodyX, bodyY + 4, 0xFF888888);
            g.text(font, Component.literal("Summon one with /animus player summon <name>."),
                    bodyX, bodyY + 16, 0xFF606060);
            return;
        }

        int y = bodyY;
        for (AnimusRoster.Entry entry : roster) {
            if (y + ROW_HEIGHT > top + CONTENT_HEIGHT - PADDING) break;
            boolean hovered = mouseX >= bodyX && mouseX <= bodyX + bodyWidth
                    && mouseY >= y && mouseY < y + ROW_HEIGHT;
            if (hovered) {
                g.fill(bodyX - 2, y - 1, bodyX + bodyWidth + 2, y + ROW_HEIGHT - 3, 0x30FFFFFF);
            }
            g.text(font, Component.literal(entry.name()), bodyX, y, 0xFFFFFFFF);
            String state = loopState(entry.uuid());
            if (!state.isEmpty()) {
                g.text(font, Component.literal(state),
                        bodyX + bodyWidth - font.width(state) - 2, y, 0xFF80CBC4);
            }
            g.text(font, Component.literal(whereabouts(entry.uuid())), bodyX, y + 11, 0xFF9E9E9E);
            y += ROW_HEIGHT;
        }
    }

    /** Busy/queued/idle from the pet's client-side agent loop (never creates one). */
    private static String loopState(UUID uuid) {
        return AgentLoopRegistry.get(uuid).map(loop -> {
            if (loop.isCompacting()) return "compacting";
            if (loop.isBusy()) return "working";
            if (loop.hasQueuedPrompts()) return "queued";
            return "idle";
        }).orElse("");
    }

    /** Position/HP line: live entity if in view, else the last server snapshot. */
    private String whereabouts(UUID uuid) {
        var live = ClientAnimusLookup.resolve(uuid);
        Player self = Minecraft.getInstance().player;
        if (live != null) {
            String dist = self != null ? (int) live.distanceTo(self) + "m away" : "in view";
            return "HP " + fmt(live.getHealth()) + "/" + fmt(live.getMaxHealth()) + " · " + dist;
        }
        return ClientAnimusLocations.get(uuid).map(s -> {
            if (!s.found()) return "missing — dead, or from another world?";
            String pos = (int) s.x() + ", " + (int) s.y() + ", " + (int) s.z();
            String dim = shortDimension(s.dimension());
            if (!s.loaded()) {
                // Asleep in unloaded chunks: the position is the persistent
                // last-seen record, vitals are unknown. Chatting revives it.
                return "asleep · " + pos + " · " + dim + " — chat to wake";
            }
            String base = "HP " + fmt(s.hp()) + "/" + fmt(s.maxHp()) + " · " + pos + " · " + dim;
            if (self != null && self.level().dimension().identifier().toString().equals(s.dimension())) {
                int dist = (int) Math.sqrt(self.distanceToSqr(s.x(), s.y(), s.z()));
                base += " · " + dist + "m away";
            }
            if (System.currentTimeMillis() - s.receivedAtMs() > SNAPSHOT_STALE_MS) {
                base += " (stale)";
            }
            return base;
        }).orElse("locating…");
    }

    private static String shortDimension(String dimension) {
        int colon = dimension.indexOf(':');
        return colon >= 0 ? dimension.substring(colon + 1) : dimension;
    }

    private static String fmt(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int y = bodyY;
            for (AnimusRoster.Entry entry : roster) {
                if (y + ROW_HEIGHT > top + CONTENT_HEIGHT - PADDING) break;
                if (event.x() >= bodyX && event.x() <= bodyX + bodyWidth
                        && event.y() >= y && event.y() < y + ROW_HEIGHT) {
                    AnimusScreen.open(entry.uuid(), entry.name());
                    return true;
                }
                y += ROW_HEIGHT;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
