package com.dwinovo.animus.client.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-side registry of {@link EntityAgentLoop} instances — one per Animus
 * the player is talking to, keyed by the stable {@code entity.getUUID()}.
 *
 * <h2>Why UUID, not network id</h2>
 * The vanilla network {@code entity.getId()} (int) is a per-session handle that
 * changes whenever the entity is recreated — including every cross-dimension
 * trip, where a non-player entity is destroyed and rebuilt with a fresh int id
 * but the same UUID. Keying the loop (and its conversation state) by UUID is
 * what lets the agent survive a Nether/End traversal: the rebuilt body resolves
 * back to the same loop via {@link ClientAnimusLookup}. The int id is only ever
 * used as an ephemeral handle for the current tick.
 *
 * <h2>Single-layer architecture</h2>
 * Each Animus carries its own conversation; the owner chats with each entity
 * directly. There is no coordinating brain — see {@link EntityAgentLoop}.
 *
 * <h2>Threading</h2>
 * Client main thread only — every entry point (payload handler, chat screen,
 * interact handler) runs on it. No locks.
 */
public final class AgentLoopRegistry {

    private static final Map<UUID, EntityAgentLoop> ENTITY_LOOPS = new HashMap<>();

    private AgentLoopRegistry() {}

    /** Create-on-first-access. The returned loop is bound to {@code entityUuid} for its lifetime. */
    public static EntityAgentLoop getOrCreate(UUID entityUuid) {
        return ENTITY_LOOPS.computeIfAbsent(entityUuid, EntityAgentLoop::new);
    }

    /** Read-only lookup; never creates. Used by the S→C result handler. */
    public static Optional<EntityAgentLoop> get(UUID entityUuid) {
        return Optional.ofNullable(ENTITY_LOOPS.get(entityUuid));
    }

    /**
     * UUIDs of the companions whose loop is mid-turn ({@link EntityAgentLoop#canInterrupt()}
     * — thinking, awaiting tool results, or with a queued prompt). These are the
     * heartbeat targets: a server-side chunk-ticket lease should be held for each
     * so the body stays loaded through the owner's think-time.
     */
    public static List<UUID> activeEntityUuids() {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, EntityAgentLoop> e : ENTITY_LOOPS.entrySet()) {
            if (e.getValue().canInterrupt()) out.add(e.getKey());
        }
        return out;
    }

    /** Drop one entity's loop (e.g. when it dies / unloads). */
    public static void dispose(UUID entityUuid) {
        ENTITY_LOOPS.remove(entityUuid);
    }

    /** Clear everything — called on world-disconnect / explicit reset. */
    public static void clear() {
        ENTITY_LOOPS.clear();
    }
}
