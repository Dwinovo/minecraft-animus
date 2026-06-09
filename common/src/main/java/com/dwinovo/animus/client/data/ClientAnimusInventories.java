package com.dwinovo.animus.client.data;

import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side mirror of each Animus entity's own inventory, keyed by the stable
 * {@code entity.getUUID()}. Fed by {@link com.dwinovo.animus.network.payload.AnimusInventoryPayload}
 * whenever the server-side inventory changes; read by the {@code get_storage}
 * tool so the client-side agent can see what its body is carrying without a
 * server round-trip.
 *
 * <h2>Why a mirror</h2>
 * The agent loop runs on the client but the authoritative inventory lives on
 * the entity (server side). The GUI uses a real {@code ChestMenu} which syncs
 * itself, but the agent may run while no GUI is open, so it needs this
 * lightweight snapshot.
 *
 * <h2>Lifecycle</h2>
 * Entries accumulate as the player interacts with Animus units. Cleared
 * wholesale on world disconnect via {@link #clear()} (wired from the same
 * place that clears {@link com.dwinovo.animus.client.agent.AgentLoopRegistry}).
 *
 * <h2>Threading</h2>
 * Client main thread only.
 */
public final class ClientAnimusInventories {

    private static final ItemStack[] EMPTY = new ItemStack[0];
    private static final Map<UUID, ItemStack[]> BY_ENTITY = new HashMap<>();

    private ClientAnimusInventories() {}

    /** Replace the cached snapshot for one entity. */
    public static void put(UUID entityUuid, ItemStack[] contents) {
        BY_ENTITY.put(entityUuid, contents);
    }

    /** Snapshot for one entity, or an empty array if unknown. Never null. */
    public static ItemStack[] get(UUID entityUuid) {
        return BY_ENTITY.getOrDefault(entityUuid, EMPTY);
    }

    public static void remove(UUID entityUuid) {
        BY_ENTITY.remove(entityUuid);
    }

    public static void clear() {
        BY_ENTITY.clear();
    }
}
