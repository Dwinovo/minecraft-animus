package com.dwinovo.animus.client.path;

import com.dwinovo.animus.network.payload.PathVizPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side store of every companion's current path overlay, keyed by body
 * UUID (one entry per companion, so several pets can show their paths at once).
 * Fed by {@link PathVizPayload#handle}; read by {@link PathVizRenderer} each
 * frame. An empty payload (no nodes/break/place) removes the entry — that's how
 * the server clears the overlay when a path ends.
 */
public final class ClientPathViz {

    /** One companion's overlay snapshot. {@code goal} may be null. */
    public record Viz(Identifier dimension, List<BlockPos> nodes, List<BlockPos> toBreak,
                      List<BlockPos> toPlace, BlockPos goal) {}

    private static final Map<UUID, Viz> ACTIVE = new ConcurrentHashMap<>();

    private ClientPathViz() {}

    public static void accept(PathVizPayload p) {
        if (p.nodes().isEmpty() && p.toBreak().isEmpty() && p.toPlace().isEmpty()) {
            ACTIVE.remove(p.companion());
            return;
        }
        ACTIVE.put(p.companion(),
                new Viz(p.dimension(), p.nodes(), p.toBreak(), p.toPlace(), p.goal().orElse(null)));
    }

    public static Collection<Viz> all() {
        return ACTIVE.values();
    }

    public static void clearAll() {
        ACTIVE.clear();
    }
}
