package com.dwinovo.animus.client.data;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-side cache of server-reported companion locations, fed by
 * {@code AnimusLocationsPayload} and read by the roster panel / chat vitals.
 * Exists because a far-away companion isn't tracked by the client at all —
 * its position only reaches us through an explicit locate round-trip.
 *
 * <p>Client main thread only. Entries are overwritten per response; staleness
 * is judged by the reader via {@link Snapshot#receivedAtMs}.
 */
public final class ClientAnimusLocations {

    /** One locate answer. {@code found=false} means no dimension had the entity loaded. */
    public record Snapshot(boolean found, double x, double y, double z,
                           String dimension, float hp, float maxHp, long receivedAtMs) {}

    private static final Map<UUID, Snapshot> CACHE = new HashMap<>();

    private ClientAnimusLocations() {}

    public static void update(UUID uuid, Snapshot snapshot) {
        CACHE.put(uuid, snapshot);
    }

    public static Optional<Snapshot> get(UUID uuid) {
        return Optional.ofNullable(CACHE.get(uuid));
    }

    public static void clear() {
        CACHE.clear();
    }
}
