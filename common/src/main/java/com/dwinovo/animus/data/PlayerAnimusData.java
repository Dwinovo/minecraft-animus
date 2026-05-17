package com.dwinovo.animus.data;

import com.dwinovo.animus.Constants;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side per-player container: holds the 6 {@link UnitConfig} slots,
 * the {@link PlayerAnimusStorage}, and the unit-id ↔ active-vanilla-entity
 * mapping used by the scheduling layer ({@code AssignTaskPayload}).
 *
 * <h2>Lifecycle</h2>
 * Created on first lookup ({@link #of}) for a player. Held in a process-wide
 * static map keyed by player UUID — survives logout/login within a server
 * session, lost on server restart. The choice is intentional for MVP; a
 * later commit elevates the data to a vanilla {@code SavedData} backed by
 * disk.
 *
 * <h2>What it stores</h2>
 * <ul>
 *   <li>{@code units} — fixed 6-entry array indexed 0..5 (representing
 *       unit_id 1..6). Each entry is the stable per-slot metadata.</li>
 *   <li>{@code storage} — shared inventory across all of this player's
 *       Animuses.</li>
 *   <li>{@code activeVanillaId} — for each slot, the vanilla
 *       {@code entity.getId()} of the currently-spawned Animus, or -1 if
 *       the slot is idle (no entity in world).</li>
 *   <li>{@code vanillaToUnitId} — reverse map so server-side events on a
 *       vanilla Animus can find "which unit slot did this come from".</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Server main thread only. All mutations come from server-thread handlers
 * (network payloads, tick callbacks). No synchronisation.
 */
public final class PlayerAnimusData {

    public static final int SLOT_COUNT = 6;

    private static final Map<UUID, PlayerAnimusData> BY_PLAYER = new HashMap<>();

    private final UUID playerUuid;
    private final UnitConfig[] units = new UnitConfig[SLOT_COUNT];
    private final PlayerAnimusStorage storage = new PlayerAnimusStorage();
    /** Index = unit_id - 1; value = vanilla {@code entity.getId()} or -1 when idle. */
    private final int[] activeVanillaId = new int[SLOT_COUNT];
    private final Map<Integer, Integer> vanillaToUnitId = new HashMap<>();

    private PlayerAnimusData(UUID playerUuid) {
        this.playerUuid = playerUuid;
        for (int i = 0; i < SLOT_COUNT; i++) {
            units[i] = UnitConfig.defaultFor(i + 1);
            activeVanillaId[i] = -1;
        }
    }

    /** Get or lazily create the data block for a player. */
    public static PlayerAnimusData of(ServerPlayer player) {
        return of(player.getUUID());
    }

    public static PlayerAnimusData of(UUID playerUuid) {
        return BY_PLAYER.computeIfAbsent(playerUuid, PlayerAnimusData::new);
    }

    /** Lookup without creation; returns empty when player has never been initialised. */
    public static Optional<PlayerAnimusData> lookup(UUID playerUuid) {
        return Optional.ofNullable(BY_PLAYER.get(playerUuid));
    }

    /**
     * Reverse-lookup the owning player for a vanilla entity id, useful when
     * the server gets a callback on an Animus and needs to find "whose
     * storage / which unit_id is this?". Linear over all players, but the
     * count is small (per-server online players).
     */
    public static Optional<UnitKey> findUnitFor(int vanillaEntityId) {
        for (var entry : BY_PLAYER.entrySet()) {
            Integer unitId = entry.getValue().vanillaToUnitId.get(vanillaEntityId);
            if (unitId != null) {
                return Optional.of(new UnitKey(entry.getKey(), unitId));
            }
        }
        return Optional.empty();
    }

    /** Drop all data — called on server shutdown. */
    public static void clearAll() {
        BY_PLAYER.clear();
    }

    // ---- per-player API ----

    public UUID playerUuid() { return playerUuid; }
    public PlayerAnimusStorage storage() { return storage; }

    /** Read-only view of the 6 configs in slot order. */
    public UnitConfig[] units() { return units; }

    /** Returns the config for unit_id (1..6) or throws if out of range. */
    public UnitConfig unit(int unitId) {
        if (unitId < 1 || unitId > SLOT_COUNT) {
            throw new IllegalArgumentException("unit_id must be 1.." + SLOT_COUNT + ", got " + unitId);
        }
        return units[unitId - 1];
    }

    /** True if unit slot has an active spawned entity in the world. */
    public boolean isActive(int unitId) {
        return activeVanillaId[unitId - 1] != -1;
    }

    /** Vanilla {@code entity.getId()} of the spawned Animus, or -1 if idle. */
    public int activeVanillaId(int unitId) {
        return activeVanillaId[unitId - 1];
    }

    /**
     * Register the mapping between a freshly-spawned Animus and a unit slot.
     * Both forward and reverse maps are updated atomically.
     */
    public void bindActive(int unitId, int vanillaEntityId) {
        if (activeVanillaId[unitId - 1] != -1) {
            Constants.LOG.warn("[animus-data] bindActive player={} unit={} replaces previous vanilla id {}",
                    playerUuid, unitId, activeVanillaId[unitId - 1]);
            vanillaToUnitId.remove(activeVanillaId[unitId - 1]);
        }
        activeVanillaId[unitId - 1] = vanillaEntityId;
        vanillaToUnitId.put(vanillaEntityId, unitId);
    }

    /**
     * Clear the active mapping for a unit slot. Called on EntityAgent
     * termination or entity removal. Idempotent.
     */
    public void unbindActive(int unitId) {
        int vanillaId = activeVanillaId[unitId - 1];
        if (vanillaId == -1) return;
        vanillaToUnitId.remove(vanillaId);
        activeVanillaId[unitId - 1] = -1;
    }

    /** Pair of (player_uuid, unit_id) — the canonical address of an Animus slot. */
    public record UnitKey(UUID playerUuid, int unitId) {}
}
