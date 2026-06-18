package com.dwinovo.animus.client.hud;

import com.dwinovo.animus.agent.llm.ConvoState;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.LlmToolCall;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.agent.AnimusRoster;
import com.dwinovo.animus.client.screen.AnimusScreen;
import com.dwinovo.animus.client.screen.RosterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Advancement-style HUD toasts: when an Animus speaks or starts an action while
 * you're NOT looking at its panel, a small card slides in top-right with its name
 * and what it's doing, then auto-dismisses. Brief by design — the full transcript
 * lives in the chat tab.
 *
 * <p>{@link #tick()} (each client tick) polls every companion's agent loop for new
 * assistant turns and enqueues a toast; {@link #render} draws + animates them. Both
 * loaders call into here from their HUD hook + client tick. Client main thread only.
 */
public final class AnimusToasts {

    private static final int W = 162;
    private static final int H = 28;
    private static final int MARGIN = 6;
    private static final int GAP = 4;
    private static final int MAX_VISIBLE = 4;
    private static final long SLIDE_MS = 220;
    private static final long HOLD_MS = 4200;
    private static final long LIFE_MS = SLIDE_MS + HOLD_MS + SLIDE_MS;

    private static final int BG = 0xF00E1116;
    private static final int BORDER = 0xFF2B313B;
    private static final int NAME = 0xFFE6E8EB;
    private static final int REPLY = 0xFF6FC3FF;
    private static final int ACTION = 0xFF7FD4C8;

    private static final Map<UUID, Integer> SEEN = new HashMap<>();
    private static final Deque<Toast> TOASTS = new ArrayDeque<>();

    private AnimusToasts() {}

    private record Toast(String name, String line, int color, long bornMs) {}

    /** Poll loops for new assistant turns, drop expired toasts. */
    public static void tick() {
        long now = System.currentTimeMillis();
        TOASTS.removeIf(t -> now - t.bornMs() > LIFE_MS);

        for (AnimusRoster.Entry entry : AnimusRoster.instance().entries()) {
            UUID uuid = entry.uuid();
            AgentLoopRegistry.get(uuid).ifPresent(loop -> {
                List<ConvoState.Msg> snap = loop.convo().snapshot();
                int prev = SEEN.getOrDefault(uuid, -1);
                if (prev < 0) {                       // first sight — don't replay the backlog
                    SEEN.put(uuid, snap.size());
                    return;
                }
                for (int i = prev; i < snap.size(); i++) {
                    if (snap.get(i) instanceof ConvoState.Msg.Assistant a) {
                        Toast t = toastFor(entry.name(), a.turn(), now);
                        if (t != null) push(t);
                    }
                }
                SEEN.put(uuid, snap.size());
            });
        }
    }

    /** One toast per assistant turn: the spoken reply if any, else the action it started. */
    private static Toast toastFor(String name, AssistantTurn turn, long now) {
        if (turn.content() != null && !turn.content().isBlank()) {
            return new Toast(name, snip(turn.content(), 34), REPLY, now);
        }
        if (!turn.toolCalls().isEmpty()) {
            LlmToolCall tc = turn.toolCalls().get(turn.toolCalls().size() - 1);
            String extra = turn.toolCalls().size() > 1 ? "  +" + (turn.toolCalls().size() - 1) : "";
            return new Toast(name, "▸ " + tc.name() + extra, ACTION, now);
        }
        return null;
    }

    private static void push(Toast t) {
        TOASTS.addLast(t);
        while (TOASTS.size() > MAX_VISIBLE) TOASTS.removeFirst();
    }

    public static void render(GuiGraphicsExtractor g) {
        if (TOASTS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AnimusScreen || mc.screen instanceof RosterScreen) return;  // already watching

        Font font = mc.font;
        long now = System.currentTimeMillis();
        int right = g.guiWidth() - MARGIN;
        int y = MARGIN;
        for (Toast t : new ArrayList<>(TOASTS)) {
            long age = now - t.bornMs();
            int off = slideOffset(age);
            int x = right - W + off;
            g.fill(x, y, x + W, y + H, BG);
            g.outline(x, y, W, H, BORDER);
            g.fill(x, y, x + 2, y + H, t.color());                     // left accent bar
            g.text(font, Component.literal(t.name()), x + 7, y + 5, NAME);
            g.text(font, Component.literal(t.line()), x + 7, y + 16, t.color());
            y += H + GAP;
        }
    }

    /** Slide-in from the right, hold, slide-out. Returns px to push the card right (0 = docked). */
    private static int slideOffset(long age) {
        if (age < SLIDE_MS) {
            float p = 1f - (float) age / SLIDE_MS;        // 1→0
            return (int) (W * p * p);
        }
        long outStart = SLIDE_MS + HOLD_MS;
        if (age > outStart) {
            float p = Math.min(1f, (float) (age - outStart) / SLIDE_MS);
            return (int) (W * p * p);
        }
        return 0;
    }

    private static String snip(String s, int max) {
        String c = s.replaceAll("\\s+", " ").trim();
        return c.length() > max ? c.substring(0, max) + "…" : c;
    }

    public static void clear() {
        SEEN.clear();
        TOASTS.clear();
    }
}
