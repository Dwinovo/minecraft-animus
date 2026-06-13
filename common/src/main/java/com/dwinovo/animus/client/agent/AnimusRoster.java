package com.dwinovo.animus.client.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side view of the owner's companions <em>currently live in the world</em>
 * — the data source for the G panel and the remote chat entry point.
 *
 * <h2>Server-detected, not persisted</h2>
 * The owner is a server-side field on each {@code AnimusPlayer}, so only the
 * server can answer "which in-world players are my companions". It detects that
 * set and ships a full snapshot via {@code CompanionListPayload} on every change
 * (login-respawn / summon / despawn / death); this class just mirrors the latest
 * snapshot. No disk persistence — the panel reflects what actually exists right
 * now, never an accumulated list of ghosts.
 *
 * <h2>Threading</h2>
 * Client main thread only.
 */
public final class AnimusRoster {

    /** One live companion: identity + display name. */
    public record Entry(UUID uuid, String name) {}

    private static AnimusRoster instance;

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    private AnimusRoster() {}

    public static AnimusRoster instance() {
        if (instance == null) {
            instance = new AnimusRoster();
        }
        return instance;
    }

    /** Replace the whole roster with a fresh server snapshot (full replacement). */
    public void replaceAll(List<Entry> snapshot) {
        entries.clear();
        for (Entry e : snapshot) {
            entries.put(e.uuid(), e);
        }
    }

    /** Drop one companion immediately (death payload), ahead of the next snapshot. */
    public void remove(UUID uuid) {
        entries.remove(uuid);
    }

    /** All live companions, in the order the server listed them. */
    public List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    public int size() {
        return entries.size();
    }
}
