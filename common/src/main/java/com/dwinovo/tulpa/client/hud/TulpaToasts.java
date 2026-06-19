package com.dwinovo.tulpa.client.hud;

import com.dwinovo.tulpa.agent.llm.ConvoState;
import com.dwinovo.tulpa.agent.provider.AssistantTurn;
import com.dwinovo.tulpa.client.agent.AgentLoopRegistry;
import com.dwinovo.tulpa.client.agent.TulpaRoster;
import com.dwinovo.tulpa.client.agent.ClientTulpaLookup;
import com.dwinovo.tulpa.client.screen.TulpaScreen;
import com.dwinovo.tulpa.client.screen.Nb;
import com.dwinovo.tulpa.client.screen.UiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Left-edge activity HUD with a three-stage lifecycle per companion:
 * <ol>
 *   <li><b>Idle</b> (no recent activity) — the avatar is retracted off-screen, leaving only a thin gold
 *       sliver at the very edge as a "still here" hint.</li>
 *   <li><b>Recently active, toast expired</b> — the avatar lingers, fully out, but no bubble.</li>
 *   <li><b>Active</b> (just spoke) — the avatar AND a toast bubble slide out together.</li>
 * </ol>
 * "Active" = any new assistant turn or the loop being busy; the avatar's lifetime ({@link #AVATAR_LIFE_MS})
 * is longer than a toast line's ({@link #LINE_LIFE_MS}), so stage 2 exists. A fresh message after a long
 * idle pulls the avatar and the bubble out together. Tool-only turns refresh activity but raise no bubble.
 */
public final class TulpaToasts {

    private static final int W = 172;            // bubble width
    private static final int AVATAR = 24;        // avatar head size
    private static final int MARGIN = 0;         // avatar flush against the left window edge (no gap)
    private static final int STACK_GAP = 8;
    private static final int BUBBLE_GAP = 5;
    private static final int TIP_W = 6, TIP_H = 11;
    private static final int SLIVER_W = 3;       // collapsed gold edge
    private static final int LINE_H = 11;
    private static final int PADV = 5;
    private static final int INNER_W = W - 14;
    private static final int MAX_LINES = 4;
    private static final int MAX_REPLY_LINES = 3;
    private static final long LINE_LIFE_MS = 5000;                  // toast line lifetime
    private static final long AVATAR_LIFE_MS = LINE_LIFE_MS + 8000; // avatar lingers 8 s past the toast
    private static final long SLIDE_MS = 220;

    private static net.minecraft.resources.Identifier spr(String n) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(com.dwinovo.tulpa.Constants.MOD_ID, n);
    }
    private static final net.minecraft.resources.Identifier BUBBLE_SPRITE = spr("bubble");
    private static final net.minecraft.resources.Identifier TIP_SPRITE = spr("bubble_tip");
    private static final net.minecraft.resources.Identifier AVATAR_FRAME = spr("avatar_frame");

    private static final Map<UUID, Integer> SEEN = new HashMap<>();
    private static final Map<UUID, Status> STATUS = new HashMap<>();

    private TulpaToasts() {}

    private record Line(String text, int color, long bornMs) {}

    private static final class Status {
        long lastActivityMs;      // any assistant turn / busy tick — drives the avatar lifetime
        long activatedMs;         // last idle→active transition — drives the avatar slide-out
        long bubbleBornMs;        // last time a bubble (re)appeared — drives the bubble slide-out
        final Deque<Line> lines = new ArrayDeque<>();
    }

    /** Expire toast lines, then poll loops for new assistant turns / busy state. */
    public static void tick() {
        long now = System.currentTimeMillis();
        STATUS.values().forEach(s -> s.lines.removeIf(l -> now - l.bornMs() > LINE_LIFE_MS));

        for (TulpaRoster.Entry entry : TulpaRoster.instance().entries()) {
            UUID uuid = entry.uuid();
            AgentLoopRegistry.get(uuid).ifPresent(loop -> {
                List<ConvoState.Msg> snap = loop.convo().snapshot();
                int prev = SEEN.getOrDefault(uuid, -1);
                if (prev >= 0) {
                    for (int i = prev; i < snap.size(); i++) {
                        if (snap.get(i) instanceof ConvoState.Msg.Assistant a) {
                            markActivity(uuid, now);
                            if (a.turn().content() != null && !a.turn().content().isBlank()) {
                                addToastLines(uuid, a.turn(), now);
                            }
                        }
                    }
                }
                SEEN.put(uuid, snap.size());
                if (loop.isBusy()) markActivity(uuid, now);   // keep the avatar out during long task runs
            });
        }
    }

    private static void markActivity(UUID uuid, long now) {
        Status s = STATUS.computeIfAbsent(uuid, k -> new Status());
        if (now - s.lastActivityMs >= AVATAR_LIFE_MS) s.activatedMs = now;   // was collapsed → start slide-out
        s.lastActivityMs = now;
    }

    private static void addToastLines(UUID uuid, AssistantTurn turn, long now) {
        UiTheme th = UiTheme.current();
        Status s = STATUS.computeIfAbsent(uuid, k -> new Status());
        boolean wasEmpty = s.lines.isEmpty();
        for (String wrapped : wrapToWidth(turn.content(), INNER_W, MAX_REPLY_LINES)) {
            s.lines.addLast(new Line(wrapped, th.reply(), now));
        }
        while (s.lines.size() > MAX_LINES) s.lines.removeFirst();
        if (wasEmpty) s.bubbleBornMs = now;                 // fresh bubble → restart the slide
    }

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TulpaScreen) return;
        List<TulpaRoster.Entry> entries = new ArrayList<>(TulpaRoster.instance().entries());
        if (entries.isEmpty()) return;

        Font font = mc.font;
        UiTheme th = UiTheme.current();
        long now = System.currentTimeMillis();
        int n = entries.size();
        int startY = (g.guiHeight() - (n * AVATAR + (n - 1) * STACK_GAP)) / 2;

        for (int i = 0; i < n; i++) {
            UUID uuid = entries.get(i).uuid();
            int ay = startY + i * (AVATAR + STACK_GAP);
            Status s = STATUS.get(uuid);
            long sinceActive = s == null ? Long.MAX_VALUE : now - s.lastActivityMs;

            if (s != null && sinceActive < AVATAR_LIFE_MS) {                 // stage 2/3 — avatar out
                int ax = MARGIN - slideOut(now - s.activatedMs, MARGIN + AVATAR);
                if (!s.lines.isEmpty()) drawBubble(g, font, ax, ay, s, now); // stage 3 — bubble too
                drawAvatar(g, uuid, ax, ay, th);
            } else {
                long collapseAge = s == null ? Long.MAX_VALUE : sinceActive - AVATAR_LIFE_MS;
                if (collapseAge < SLIDE_MS) {                                // retracting off-screen
                    int ax = MARGIN - ((MARGIN + AVATAR) - slideOut(collapseAge, MARGIN + AVATAR));
                    drawAvatar(g, uuid, ax, ay, th);
                } else {                                                     // stage 1 — gold sliver only
                    g.fill(0, ay + 2, SLIVER_W, ay + AVATAR - 2, th.cta());
                }
            }
        }
    }

    private static void drawAvatar(GuiGraphicsExtractor g, UUID uuid, int x, int y, UiTheme th) {
        // textured socket behind the head (same sprite as the panel rail), face on top covering the centre
        g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                AVATAR_FRAME, x - 2, y - 2, AVATAR + 4, AVATAR + 4);
        PlayerFaceExtractor.extractRenderState(g, skinFor(uuid), x, y, AVATAR);
    }


    private static void drawBubble(GuiGraphicsExtractor g, Font font, int ax, int ay, Status s, long now) {
        int h = s.lines.size() * LINE_H + PADV * 2;
        int targetX = ax + AVATAR + BUBBLE_GAP;
        int bx = targetX - slideOut(now - s.bubbleBornMs, AVATAR + BUBBLE_GAP);
        int by = ay + AVATAR / 2 - h / 2;
        var pipe = net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;
        g.blitSprite(pipe, TIP_SPRITE, bx - TIP_W + 1, ay + AVATAR / 2 - TIP_H / 2, TIP_W, TIP_H);
        g.blitSprite(pipe, BUBBLE_SPRITE, bx, by, W, h);
        int ly = by + PADV;
        for (Line line : s.lines) {
            Nb.text(g, font, line.text(), bx + 7, ly, line.color());
            ly += LINE_H;
        }
    }

    private static PlayerSkin skinFor(UUID uuid) {
        AbstractClientPlayer e = ClientTulpaLookup.resolve(uuid);
        return e != null ? e.getSkin() : DefaultPlayerSkin.get(uuid);
    }

    /** Eased slide: {@code dist} px → 0 over {@link #SLIDE_MS}. */
    private static int slideOut(long age, int dist) {
        if (age >= SLIDE_MS) return 0;
        float p = 1f - (float) age / SLIDE_MS;
        return (int) (dist * p * p);
    }

    /** Greedily wrap to a pixel width (CJK-aware), capped at {@code maxLines}; ellipsis if truncated. */
    private static List<String> wrapToWidth(String text, int maxW, int maxLines) {
        Font font = Minecraft.getInstance().font;
        String s = text.replaceAll("\\s+", " ").trim();
        List<String> out = new ArrayList<>();
        while (!s.isEmpty() && out.size() < maxLines) {
            String head = font.plainSubstrByWidth(s, maxW);
            if (head.isEmpty()) head = s.substring(0, 1);
            out.add(head.trim());
            s = s.substring(head.length());
        }
        if (!s.isEmpty() && !out.isEmpty()) {
            String last = out.get(out.size() - 1);
            while (!last.isEmpty() && font.width(last + "…") > maxW) last = last.substring(0, last.length() - 1);
            out.set(out.size() - 1, last + "…");
        }
        return out;
    }

    public static void clear() {
        SEEN.clear();
        STATUS.clear();
    }
}
