package com.dwinovo.animus.client.hud;

import com.dwinovo.animus.agent.llm.ConvoState;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.agent.AnimusRoster;
import com.dwinovo.animus.client.agent.ClientAnimusLookup;
import com.dwinovo.animus.client.screen.AnimusScreen;
import com.dwinovo.animus.client.screen.Nb;
import com.dwinovo.animus.client.screen.RosterScreen;
import com.dwinovo.animus.client.screen.UiTheme;
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
 * Left-edge activity HUD. Every roster companion shows a small persistent avatar head stacked around the
 * screen's vertical middle (when not watching a panel). When a companion SPEAKS, a toast bubble pops out
 * to the right of its avatar — wrapped, vertically-centred reply text — and fades line-by-line. Tool-only
 * turns are ignored. The avatar stays even when idle.
 */
public final class AnimusToasts {

    private static final int W = 172;            // bubble width
    private static final int AVATAR = 24;        // avatar head size
    private static final int MARGIN = 6;         // screen-edge inset
    private static final int STACK_GAP = 8;      // between stacked avatars
    private static final int BUBBLE_GAP = 5;     // avatar → bubble
    private static final int LINE_H = 11;
    private static final int PADV = 5;           // bubble top/bottom padding (vertical centring)
    private static final int INNER_W = W - 14;   // text column (7px each side)
    private static final int MAX_LINES = 4;
    private static final int MAX_REPLY_LINES = 3;
    private static final long LINE_LIFE_MS = 5000;
    private static final long SLIDE_MS = 220;

    private static final net.minecraft.resources.Identifier BUBBLE_SPRITE =
            net.minecraft.resources.Identifier.fromNamespaceAndPath(com.dwinovo.animus.Constants.MOD_ID, "bubble");

    private static final Map<UUID, Integer> SEEN = new HashMap<>();
    private static final Map<UUID, Card> CARDS = new HashMap<>();

    private AnimusToasts() {}

    private record Line(String text, int color, long bornMs) {}

    private static final class Card {
        long bornMs;                                     // when the bubble (re)appeared — drives the slide
        final Deque<Line> lines = new ArrayDeque<>();    // oldest first
    }

    /** Drop expired lines / empty cards, then poll loops for new assistant turns. */
    public static void tick() {
        long now = System.currentTimeMillis();
        CARDS.values().forEach(c -> c.lines.removeIf(l -> now - l.bornMs() > LINE_LIFE_MS));
        CARDS.values().removeIf(c -> c.lines.isEmpty());

        for (AnimusRoster.Entry entry : AnimusRoster.instance().entries()) {
            UUID uuid = entry.uuid();
            AgentLoopRegistry.get(uuid).ifPresent(loop -> {
                List<ConvoState.Msg> snap = loop.convo().snapshot();
                int prev = SEEN.getOrDefault(uuid, -1);
                if (prev < 0) { SEEN.put(uuid, snap.size()); return; }   // skip backlog on first sight
                for (int i = prev; i < snap.size(); i++) {
                    if (snap.get(i) instanceof ConvoState.Msg.Assistant a) {
                        addLine(uuid, a.turn(), now);
                    }
                }
                SEEN.put(uuid, snap.size());
            });
        }
    }

    /** Append a spoken reply (wrapped to the bubble width, sharing one lifetime); tool-only turns ignored. */
    private static void addLine(UUID uuid, AssistantTurn turn, long now) {
        if (turn.content() == null || turn.content().isBlank()) return;   // text-only toasts
        UiTheme th = UiTheme.current();
        Card card = CARDS.get(uuid);
        if (card == null || card.lines.isEmpty()) {        // a fresh bubble — (re)start the slide
            card = new Card();
            card.bornMs = now;
            CARDS.put(uuid, card);
        }
        for (String wrapped : wrapToWidth(turn.content(), INNER_W, MAX_REPLY_LINES)) {
            card.lines.addLast(new Line(wrapped, th.reply(), now));
        }
        while (card.lines.size() > MAX_LINES) card.lines.removeFirst();
    }

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AnimusScreen || mc.screen instanceof RosterScreen) return;
        List<AnimusRoster.Entry> entries = new ArrayList<>(AnimusRoster.instance().entries());
        if (entries.isEmpty()) return;

        Font font = mc.font;
        UiTheme th = UiTheme.current();
        long now = System.currentTimeMillis();
        int n = entries.size();
        int totalH = n * AVATAR + (n - 1) * STACK_GAP;
        int startY = (g.guiHeight() - totalH) / 2;

        for (int i = 0; i < n; i++) {
            UUID uuid = entries.get(i).uuid();
            int ay = startY + i * (AVATAR + STACK_GAP);

            // bubble FIRST (so it emerges from BEHIND the avatar), only while it has live lines
            Card card = CARDS.get(uuid);
            if (card != null && !card.lines.isEmpty()) {
                int h = card.lines.size() * LINE_H + PADV * 2;
                int targetX = MARGIN + AVATAR + BUBBLE_GAP;
                int bx = targetX - slideOut(now - card.bornMs, AVATAR + BUBBLE_GAP);   // slide out from avatar
                int by = ay + AVATAR / 2 - h / 2;                                       // centred on the avatar
                g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, BUBBLE_SPRITE, bx, by, W, h);
                int ly = by + PADV;
                for (Line line : card.lines) {
                    Nb.text(g, font, line.text(), bx + 7, ly, line.color());
                    ly += LINE_H;
                }
            }

            // avatar ON TOP — companion head, framed
            PlayerSkin skin = skinFor(uuid);
            PlayerFaceExtractor.extractRenderState(g, skin, MARGIN, ay, AVATAR);
            Nb.border(g, MARGIN, ay, AVATAR, AVATAR, 2, th.border());
        }
    }

    private static PlayerSkin skinFor(UUID uuid) {
        AbstractClientPlayer e = ClientAnimusLookup.resolve(uuid);
        return e != null ? e.getSkin() : DefaultPlayerSkin.get(uuid);
    }

    /** Eased horizontal slide-out: {@code dist} px → 0 over {@link #SLIDE_MS}. */
    private static int slideOut(long age, int dist) {
        if (age >= SLIDE_MS) return 0;
        float p = 1f - (float) age / SLIDE_MS;             // 1 → 0
        return (int) (dist * p * p);
    }

    /** Greedily wrap to a pixel width (handles CJK / wide glyphs), capped at {@code maxLines}; the last
     *  line gets an ellipsis if the text was truncated. */
    private static List<String> wrapToWidth(String text, int maxW, int maxLines) {
        Font font = Minecraft.getInstance().font;
        String s = text.replaceAll("\\s+", " ").trim();
        List<String> out = new ArrayList<>();
        while (!s.isEmpty() && out.size() < maxLines) {
            String head = font.plainSubstrByWidth(s, maxW);
            if (head.isEmpty()) head = s.substring(0, 1);   // never stall
            out.add(head.trim());
            s = s.substring(head.length());
        }
        if (!s.isEmpty() && !out.isEmpty()) {               // truncated — ellipsise the last line
            String last = out.get(out.size() - 1);
            while (!last.isEmpty() && font.width(last + "…") > maxW) last = last.substring(0, last.length() - 1);
            out.set(out.size() - 1, last + "…");
        }
        return out;
    }

    public static void clear() {
        SEEN.clear();
        CARDS.clear();
    }
}
