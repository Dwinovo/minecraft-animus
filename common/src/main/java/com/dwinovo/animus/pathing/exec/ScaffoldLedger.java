package com.dwinovo.animus.pathing.exec;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers which blocks the PATHFINDER placed (bridges, pillars, step-ups)
 * so that auto-targeting tools never treat the pet's own infrastructure as
 * loot. The concrete failure this kills: pet bridges over water to a target,
 * then {@code auto_mine(cobblestone)} scans, finds the nearest cobble — its
 * own bridge — and digs up its way home.
 *
 * <p>No bot we surveyed automates this (Baritone lists it as a known gap;
 * Altoclef has the {@code avoidBlockBreaking} API but nothing registers
 * placements into it); the design follows Altoclef's API shape with the
 * registration made automatic at the placement site ({@link PathExecutor}).
 *
 * <h2>Self-healing</h2>
 * Entries are validated on read against the block CURRENTLY at the position
 * ({@link #isOwnScaffold}): if the world no longer holds what we placed
 * (someone mined it, it fell, it was replaced), the entry evicts itself.
 * Capacity-bounded (LRU by insertion) and deliberately NOT persisted — after
 * a restart old bridges decay into ordinary world blocks, which is fine
 * because the explicit {@code break_block} escape hatch can always reclaim
 * them and the stance discipline still protects the block underfoot.
 *
 * <p>Generic over the block token type so the LRU/self-heal logic is unit
 * testable without bootstrapping the block registry; production code uses
 * {@code ScaffoldLedger<Block>}.
 */
public final class ScaffoldLedger<T> {

    /** Plenty for any realistic route history; ~16 bytes/entry, LRU evicted. */
    private static final int CAPACITY = 512;

    private final Map<BlockPos, T> placed = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockPos, T> eldest) {
            return size() > CAPACITY;
        }
    };

    /** Record a block the pathfinder just placed. */
    public void record(BlockPos pos, T block) {
        placed.put(pos.immutable(), block);
    }

    /**
     * Is {@code pos} one of our own placed scaffold blocks, given the block
     * type CURRENTLY there? A mismatch (world changed since placement) evicts
     * the stale entry and answers false.
     */
    public boolean isOwnScaffold(BlockPos pos, T currentBlock) {
        T recorded = placed.get(pos);
        if (recorded == null) return false;
        if (recorded != currentBlock) {
            placed.remove(pos);
            return false;
        }
        return true;
    }

    public int size() {
        return placed.size();
    }
}
