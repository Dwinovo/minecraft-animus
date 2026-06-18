package com.dwinovo.tulpa.client.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side registry of companions currently dead and counting down to respawn
 * (companion UUID → respawn epoch millis). Fed by {@code TulpaDeathPayload}, cleared by
 * {@code TulpaRespawnPayload}. Read by the HUD and the panel rail to show the death state —
 * a dimmed avatar with a respawn countdown. Client main thread only.
 */
public final class ClientDeaths {

    private static final Map<UUID, Long> RESPAWN_AT = new HashMap<>();

    private ClientDeaths() {}

    public static void markDead(UUID uuid, long respawnAtMs) { RESPAWN_AT.put(uuid, respawnAtMs); }

    public static void clear(UUID uuid) { RESPAWN_AT.remove(uuid); }

    public static boolean isDead(UUID uuid) { return RESPAWN_AT.containsKey(uuid); }

    /** Remaining millis until respawn (clamped ≥ 0), or -1 if not dead. */
    public static long remainingMs(UUID uuid) {
        Long at = RESPAWN_AT.get(uuid);
        return at == null ? -1 : Math.max(0, at - System.currentTimeMillis());
    }

    public static void clearAll() { RESPAWN_AT.clear(); }
}
