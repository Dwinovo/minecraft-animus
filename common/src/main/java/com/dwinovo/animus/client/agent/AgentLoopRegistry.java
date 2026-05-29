package com.dwinovo.animus.client.agent;

import com.dwinovo.animus.client.data.ClientAnimusInventories;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client-side registry of {@link EntityAgentLoop} instances — one per Animus
 * the player is talking to, keyed by the vanilla {@code entity.getId()}.
 *
 * <h2>Single-layer architecture</h2>
 * Each Animus carries its own conversation; the owner chats with each entity
 * directly. There is no coordinating brain — see {@link EntityAgentLoop}.
 *
 * <h2>Threading</h2>
 * Client main thread only — every entry point (payload handler, chat screen,
 * interact handler, client-tick watchdog) runs on it. No locks.
 */
public final class AgentLoopRegistry {

    private static final Map<Integer, EntityAgentLoop> ENTITY_LOOPS = new HashMap<>();

    private AgentLoopRegistry() {}

    /** Create-on-first-access. The returned loop is bound to {@code vanillaEntityId} for its lifetime. */
    public static EntityAgentLoop getOrCreate(int vanillaEntityId) {
        return ENTITY_LOOPS.computeIfAbsent(vanillaEntityId, EntityAgentLoop::new);
    }

    /** Read-only lookup; never creates. Used by the S→C result handler. */
    public static Optional<EntityAgentLoop> get(int vanillaEntityId) {
        return Optional.ofNullable(ENTITY_LOOPS.get(vanillaEntityId));
    }

    /** Drop one entity's loop + inventory mirror (e.g. when it dies / unloads). */
    public static void dispose(int vanillaEntityId) {
        ENTITY_LOOPS.remove(vanillaEntityId);
        ClientAnimusInventories.remove(vanillaEntityId);
    }

    /** Clear everything — called on world-disconnect / explicit reset. */
    public static void clear() {
        ENTITY_LOOPS.clear();
        ClientAnimusInventories.clear();
    }

    /**
     * Per-client-tick fan-out. Each entity loop runs its stale-result
     * watchdog. Snapshots the values so a loop mutating the map mid-iteration
     * doesn't blow up the iterator.
     */
    public static void tickAll() {
        if (ENTITY_LOOPS.isEmpty()) return;
        EntityAgentLoop[] snapshot = ENTITY_LOOPS.values().toArray(new EntityAgentLoop[0]);
        for (EntityAgentLoop loop : snapshot) {
            loop.tick();
        }
    }
}
