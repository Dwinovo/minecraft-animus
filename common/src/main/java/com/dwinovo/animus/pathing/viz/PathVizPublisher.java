package com.dwinovo.animus.pathing.viz;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.network.payload.PathVizPayload;
import com.dwinovo.animus.pathing.calc.Path;
import com.dwinovo.animus.pathing.movement.Movement;
import com.dwinovo.animus.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Server side of the path overlay: turns a computed {@link Path} into a {@link
 * PathVizPayload} and pushes it to the companion's owner. Baritone renders its
 * path client-side from its own state; our path is computed server-side, so the
 * body publishes whenever it (re)plans a segment and clears when the path ends.
 * No-op when the owner is offline.
 */
public final class PathVizPublisher {

    private PathVizPublisher() {}

    /** Push the current path + the goal it's heading for. */
    public static void publish(AnimusPlayer player, Path path, BlockPos goal) {
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
                cap(nodes), cap(toBreak), cap(toPlace),
                Optional.ofNullable(goal)));
    }

    /** Clear the overlay (empty lists, no goal). */
    public static void clear(AnimusPlayer player) {
        ServerPlayer owner = player.resolveOwnerPlayer();
        if (owner == null) return;
        Services.NETWORK.sendToPlayer(owner, new PathVizPayload(
                player.getUUID(), player.level().dimension().identifier(),
                List.of(), List.of(), List.of(), Optional.empty()));
    }

    private static List<BlockPos> cap(List<BlockPos> list) {
        return list.size() <= PathVizPayload.MAX ? list : list.subList(0, PathVizPayload.MAX);
    }
}
