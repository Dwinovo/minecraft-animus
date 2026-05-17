package com.dwinovo.animus.client.agent;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.network.payload.UnitDiedPayload;
import com.dwinovo.animus.network.payload.UnitSpawnedPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client-side registry for the multi-agent stack:
 * <ul>
 *   <li>One {@link PlayerAgentLoop} singleton for the local player.</li>
 *   <li>One {@link EntityAgentLoop} per currently-spawned Animus, keyed by
 *       the vanilla {@code entity.getId()} (server-assigned at spawn time).</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Client main thread only — every entry point (payload handler, EntityAgent
 * self-dispose, etc.) runs on it.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>PlayerAgent: lazily created on first {@link #playerAgent} call;
 *       lives the whole session.</li>
 *   <li>EntityAgents: created in {@link #onUnitSpawned} when the server
 *       confirms a successful summon; disposed in {@link #disposeEntityLoop}
 *       when the loop emits final text, or in {@link #onUnitDied} when the
 *       in-world entity dies unexpectedly.</li>
 *   <li>{@link #clear} called from world-disconnect / fresh-start hooks.</li>
 * </ul>
 */
public final class AgentLoopRegistry {

    private static PlayerAgentLoop PLAYER;
    private static final Map<Integer, EntityAgentLoop> ENTITY_LOOPS = new HashMap<>();

    private AgentLoopRegistry() {}

    /** Get-or-create the singleton PlayerAgent for this client session. */
    public static synchronized PlayerAgentLoop playerAgent() {
        if (PLAYER == null) PLAYER = new PlayerAgentLoop();
        return PLAYER;
    }

    public static Optional<EntityAgentLoop> entityLoop(int vanillaEntityId) {
        return Optional.ofNullable(ENTITY_LOOPS.get(vanillaEntityId));
    }

    /** S→C handler: a summon-unit just succeeded (or failed). */
    public static void onUnitSpawned(UnitSpawnedPayload p) {
        PlayerAgentLoop player = playerAgent();
        String pending = player.drainPendingPrompt(p.unitId());
        if (p.vanillaEntityId() == -1) {
            // Server reported failure — synthesise a tool-result-like report
            // so the LLM sees the failure and can adjust.
            String reason = p.failReason().isEmpty() ? "unknown" : p.failReason();
            player.injectSubagentReport(p.unitId(), "summon_failed", reason);
            Constants.LOG.warn("[animus-registry] summon failed unit={} reason={}",
                    p.unitId(), reason);
            return;
        }
        EntityAgentLoop loop = new EntityAgentLoop(p.vanillaEntityId(), p.unitId());
        ENTITY_LOOPS.put(p.vanillaEntityId(), loop);
        Constants.LOG.info("[animus-registry] spawned EntityAgent unit={} vanilla={} pending_prompt={}",
                p.unitId(), p.vanillaEntityId(), pending != null);
        if (pending != null) {
            loop.submitPrompt(pending);
        } else {
            // No pending prompt — this can happen if the player triggered a
            // bare summon outside of assign_task. Push a synthetic prompt
            // so the loop has something to chew on, or just dispose silently.
            Constants.LOG.warn("[animus-registry] spawned unit={} with no pending prompt; idling",
                    p.unitId());
        }
    }

    /** S→C handler: in-world entity died unexpectedly. */
    public static void onUnitDied(UnitDiedPayload p) {
        // Find the EntityAgent for this unit_id (by scanning — small map).
        EntityAgentLoop dying = null;
        for (EntityAgentLoop loop : ENTITY_LOOPS.values()) {
            if (loop.unitId() == p.unitId()) {
                dying = loop;
                break;
            }
        }
        if (dying != null) {
            // Entity already gone server-side; don't send a recall packet.
            dying.externalAbort("died", p.reason(), false);
        } else {
            // Even with no EntityAgent (already disposed), tell PlayerAgent.
            PlayerAgentLoop player = playerAgent();
            player.injectSubagentReport(p.unitId(), "died", p.reason());
        }
    }

    /** EntityAgent finished naturally — remove from the map. */
    public static void disposeEntityLoop(int vanillaEntityId) {
        ENTITY_LOOPS.remove(vanillaEntityId);
    }

    /** Clear everything — called on world-disconnect / explicit reset. */
    public static void clear() {
        PLAYER = null;
        ENTITY_LOOPS.clear();
    }

    /** Used by TaskResultPayload routing — find the EntityAgent that owns a vanilla id. */
    public static Optional<EntityAgentLoop> get(int vanillaEntityId) {
        return Optional.ofNullable(ENTITY_LOOPS.get(vanillaEntityId));
    }

    /** Find the EntityAgent for a given unit slot (1..6), if active. */
    public static Optional<EntityAgentLoop> findByUnitId(int unitId) {
        for (EntityAgentLoop loop : ENTITY_LOOPS.values()) {
            if (loop.unitId() == unitId) return Optional.of(loop);
        }
        return Optional.empty();
    }
}
