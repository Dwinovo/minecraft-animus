package com.dwinovo.numen.core.pathing.viz;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.PathVizPayload;
import com.dwinovo.numen.core.pathing.calc.Path;
import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Server side of the path overlay: turns a computed {@link Path} into a {@link
 * PathVizPayload} and pushes it to the companion's owner. Baritone renders its
 * path client-side from its own state; our path is computed server-side, so the
 * body publishes whenever it (re)plans a segment and clears when the path ends.
 * No-op when the owner is offline.
 */
public final class PathVizPublisher {

    private PathVizPublisher() {}

    /**
     * Push the current path plus the {@code targets} to highlight — for mining,
     * EVERY known ore/log cell (Baritone boxes every {@code GoalComposite}
     * member, so the owner sees the whole field it will work through); for a
     * plain move, just the destination cell.
     */
    public static void publish(NumenPlayer player, Path path, List<BlockPos> targets) {
        ServerPlayer owner = player.resolveOwnerPlayer();
        if (owner == null || path == null || path.isEmpty()) return;

        List<BlockPos> nodes = new ArrayList<>(path.movements.size() + 1);
        nodes.add(path.start);
        for (Movement m : path.movements) nodes.add(m.dest);

        List<BlockPos> toBreak = new ArrayList<>();
        List<BlockPos> toPlace = new ArrayList<>();
        for (Movement m : path.movements) {
            toBreak.addAll(m.toBreak);
            if (m.toPlace != null) toPlace.add(m.toPlace);
        }

        Services.NETWORK.sendToPlayer(owner, new PathVizPayload(
                player.getUUID(), player.level().dimension().identifier(),
                cap(nodes), cap(toBreak), cap(toPlace), cap(targets)));
    }

    /**
     * Publish ONLY the goal boxes, with no path line — Baritone keeps the goal
     * box rendered while the path executor is paused (e.g. mining a block in
     * place / shaft-mining), and only the path LINE comes and goes. During
     * shaft mining we hold no path but must keep the ore field boxed, exactly
     * like Baritone's {@code drawGoal(behavior.getGoal())} surviving a
     * {@code REQUEST_PAUSE}.
     */
    public static void publishTargets(NumenPlayer player, List<BlockPos> targets) {
        ServerPlayer owner = player.resolveOwnerPlayer();
        if (owner == null) return;
        Services.NETWORK.sendToPlayer(owner, new PathVizPayload(
                player.getUUID(), player.level().dimension().identifier(),
                List.of(), List.of(), List.of(), cap(targets)));
    }

    /** Clear the overlay (all lists empty). */
    public static void clear(NumenPlayer player) {
        ServerPlayer owner = player.resolveOwnerPlayer();
        if (owner == null) return;
        Services.NETWORK.sendToPlayer(owner, new PathVizPayload(
                player.getUUID(), player.level().dimension().identifier(),
                List.of(), List.of(), List.of(), List.of()));
    }

    private static List<BlockPos> cap(List<BlockPos> list) {
        return list.size() <= PathVizPayload.MAX ? list : list.subList(0, PathVizPayload.MAX);
    }
}
