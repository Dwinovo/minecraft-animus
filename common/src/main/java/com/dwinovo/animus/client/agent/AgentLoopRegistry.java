package com.dwinovo.animus.client.agent;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.network.payload.UnitDiedPayload;
import com.dwinovo.animus.network.payload.UnitSpawnedPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client-side registry of {@link EntityAgentLoop} instances — one per Animus
 * the player is talking to, keyed by the vanilla {@code entity.getId()}.
 *
 * <h2>Single-layer architecture</h2>
 * Each Animus carries its own conversation; the owner chats with each entity
 * directly. There is no PlayerAgent brain coordinating sub-agents anymore —
 * see {@link EntityAgentLoop} for the rationale behind rolling that back.
 *
 * <h2>Unit-slot index</h2>
 * Units summoned through the manager GUI are bound to a slot id (1..6). We
 * keep a {@code unitId → vanillaEntityId} index so the Units tab can open a
 * chat for, or look up, the entity currently occupying a slot. Entities the
 * player interacts with outside the slot system (right-click a stray Animus)
 * still get a loop via {@link #getOrCreate} — they just aren't in this index.
 *
 * <h2>Threading</h2>
 * Client main thread only — every entry point (payload handler, chat screen,
 * interact handler, client-tick watchdog) runs on it. No locks.
 */
public final class AgentLoopRegistry {

    private static final Map<Integer, EntityAgentLoop> ENTITY_LOOPS = new HashMap<>();
    private static final Map<Integer, Integer> UNIT_TO_ENTITY = new HashMap<>();

    private AgentLoopRegistry() {}

    /** Create-on-first-access. The returned loop is bound to {@code vanillaEntityId} for its lifetime. */
    public static EntityAgentLoop getOrCreate(int vanillaEntityId) {
        return ENTITY_LOOPS.computeIfAbsent(vanillaEntityId, EntityAgentLoop::new);
    }

    /** Read-only lookup; never creates. Used by the S→C result handler. */
    public static Optional<EntityAgentLoop> get(int vanillaEntityId) {
        return Optional.ofNullable(ENTITY_LOOPS.get(vanillaEntityId));
    }

    /** Vanilla entity id currently bound to a unit slot (1..6), if any. */
    public static Optional<Integer> entityIdForUnit(int unitId) {
        return Optional.ofNullable(UNIT_TO_ENTITY.get(unitId));
    }

    /**
     * S→C handler: a summon-unit request resolved. On success we pre-create
     * the entity's loop and index it by slot so the GUI can chat with it; on
     * failure we just log (the owner will see the unit stay idle).
     */
    public static void onUnitSpawned(UnitSpawnedPayload p) {
        if (p.vanillaEntityId() == -1) {
            String reason = p.failReason().isEmpty() ? "unknown" : p.failReason();
            Constants.LOG.warn("[animus-registry] summon failed unit={} reason={}", p.unitId(), reason);
            return;
        }
        UNIT_TO_ENTITY.put(p.unitId(), p.vanillaEntityId());
        getOrCreate(p.vanillaEntityId());
        Constants.LOG.info("[animus-registry] unit {} spawned as vanilla entity {}",
                p.unitId(), p.vanillaEntityId());
    }

    /** S→C handler: in-world entity bound to a unit slot died / unloaded. */
    public static void onUnitDied(UnitDiedPayload p) {
        Integer vanillaId = UNIT_TO_ENTITY.remove(p.unitId());
        if (vanillaId != null) {
            ENTITY_LOOPS.remove(vanillaId);
        }
        Constants.LOG.info("[animus-registry] unit {} died (reason={}); loop disposed",
                p.unitId(), p.reason());
    }

    /** Clear everything — called on world-disconnect / explicit reset. */
    public static void clear() {
        ENTITY_LOOPS.clear();
        UNIT_TO_ENTITY.clear();
    }

    /**
     * Per-client-tick fan-out. Invoked once per client tick from the loader's
     * tick event. Each entity loop runs its stale-result watchdog. Snapshots
     * the values to a fresh array so a loop mutating the map mid-iteration
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
