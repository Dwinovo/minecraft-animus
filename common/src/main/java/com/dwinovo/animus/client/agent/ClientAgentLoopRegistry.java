package com.dwinovo.animus.client.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client-side mod-global registry of {@link ClientAgentLoop} instances, one
 * per Animus entity the player has interacted with. Lookup key is the
 * vanilla {@code entity.getId()} int — stable for the lifetime of a session
 * (since the entity is loaded while the player interacts with it).
 *
 * <h2>Why per-entity</h2>
 * Each Animus carries its own conversation. A player owning two Animuses
 * should have two independent LLM threads going.
 *
 * <h2>Lifecycle</h2>
 * Lazy creation on first {@link #getOrCreate}. No automatic cleanup — when
 * the player disconnects, the JVM is typically about to exit or the user
 * is starting fresh. Stale loops accumulate slowly (one per right-clicked
 * Animus per session); manual cleanup hooks come in Phase-2 via a
 * {@code /animus reset} command and a world-disconnect lifecycle event.
 *
 * <h2>Threading</h2>
 * Client main thread only. All callers (PromptScreen, TaskResultPayload
 * handler, ClientAgentLoop itself) are guaranteed to run on it. No locks.
 */
public final class ClientAgentLoopRegistry {

    private static final Map<Integer, ClientAgentLoop> LOOPS = new HashMap<>();

    private ClientAgentLoopRegistry() {}

    /** Create-on-first-access. The returned loop is bound to {@code entityId} forever. */
    public static ClientAgentLoop getOrCreate(int entityId) {
        return LOOPS.computeIfAbsent(entityId, ClientAgentLoop::new);
    }

    /** Read-only lookup; never creates. Used by the S→C result handler. */
    public static Optional<ClientAgentLoop> get(int entityId) {
        return Optional.ofNullable(LOOPS.get(entityId));
    }

    /** Drop all stored loops. Called from world-disconnect / explicit reset. */
    public static void clear() {
        LOOPS.clear();
    }
}
