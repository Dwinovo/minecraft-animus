package com.dwinovo.animus.client.data;

import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side mirror of each Animus entity's own inventory, keyed by the
 * vanilla {@code entity.getId()}. Fed by {@link com.dwinovo.animus.network.payload.AnimusInventoryPayload}
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
    private static final Map<Integer, ItemStack[]> BY_ENTITY = new HashMap<>();

    private ClientAnimusInventories() {}

    /** Replace the cached snapshot for one entity. */
    public static void put(int entityId, ItemStack[] contents) {
        BY_ENTITY.put(entityId, contents);
    }

    /** Snapshot for one entity, or an empty array if unknown. Never null. */
    public static ItemStack[] get(int entityId) {
        return BY_ENTITY.getOrDefault(entityId, EMPTY);
    }

    public static void remove(int entityId) {
        BY_ENTITY.remove(entityId);
    }

    public static void clear() {
        BY_ENTITY.clear();
    }
}
