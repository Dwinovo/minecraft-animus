package com.dwinovo.animus.client.data;

import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-side cache of a companion's backpack, fed by {@code AnimusInventoryPayload}
 * and read by the Items tab. Other players' inventories aren't synced to clients,
 * so this only holds what an explicit request fetched. Client main thread only.
 */
public final class ClientAnimusInventory {

    /** {@code loaded=false} = the body is asleep / not ours (no contents). */
    public record Snapshot(boolean loaded, List<ItemStack> items, long receivedAtMs) {}

    private static final Map<UUID, Snapshot> CACHE = new HashMap<>();

    private ClientAnimusInventory() {}

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
