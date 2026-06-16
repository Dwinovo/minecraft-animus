package com.dwinovo.animus.pathing.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-{@code ServerLevel} store of {@link CompactSection}s — the "常驻 CachedWorld" the off-thread
 * planner reads (see {@code docs/PATHFINDING_ASYNC.md} §5.1). A single-writer / many-reader map:
 * the main thread fills and refreshes it (chunk-load / block-change hooks, P-B2), worker threads only
 * read it (P-C). {@link ConcurrentHashMap} gives lock-free reads + safe concurrent writes; each value
 * is an immutable {@link CompactSection}, so a refresh atomically swaps a whole new section in.
 *
 * <p>This class is deliberately MC-light: it only moves {@link CompactSection}s in and out by section
 * coordinate. Turning a live chunk into {@link CompactSection}s (which touches the version-sensitive
 * section-index APIs) lives in the loader wiring, which builds them and calls {@link #put}.
 */
public final class SectionCache {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Baritone {@code CachedWorld.prune}: drop sections farther than this (blocks, horizontal) from
     *  every companion. Matches Baritone's {@code dist > 1024}. */
    public static final double PRUNE_DISTANCE = 1024.0;

    private final ConcurrentHashMap<Long, CompactSection> sections = new ConcurrentHashMap<>();
    /** Section keys whose live contents changed and need re-encoding on the main thread (P-B2).
     *  Swapped out wholesale by {@link #drainDirty} so a mark landing mid-drain can't be lost. */
    private volatile Set<Long> dirty = ConcurrentHashMap.newKeySet();

    /** Block state at world {@code (x,y,z)}, or AIR if that section isn't cached (Baritone's
     *  miss → AIR). Race-free for off-thread readers. */
    public BlockState get(int x, int y, int z) {
        CompactSection cs = sections.get(sectionKey(x, y, z));
        return cs == null ? AIR : cs.get(x & 15, y & 15, z & 15);
    }

    /** True if this section is cached (used to distinguish "known air" from "unknown"). */
    public boolean isCached(int secX, int secY, int secZ) {
        return sections.containsKey(SectionPos.asLong(secX, secY, secZ));
    }

    /** Install / replace one section (main thread). Atomic swap — readers see old or new, never torn. */
    public void put(int secX, int secY, int secZ, CompactSection section) {
        sections.put(SectionPos.asLong(secX, secY, secZ), section);
    }

    /** Drop one section (e.g. it became all-air). */
    public void remove(int secX, int secY, int secZ) {
        sections.remove(SectionPos.asLong(secX, secY, secZ));
    }

    // ---- dirty tracking (block-change mixin → main-thread re-encode) ----

    /** Mark the section containing world {@code (x,y,z)} dirty (called from the setBlockState hook). */
    public void markDirty(int x, int y, int z) {
        dirty.add(sectionKey(x, y, z));
    }

    /** Take the pending dirty section keys (for the main thread to re-encode), clearing them. Atomic
     *  swap rather than snapshot-then-remove: a {@link #markDirty} arriving mid-drain lands in the fresh
     *  set and is handled next round, never silently dropped. */
    public long[] drainDirty() {
        Set<Long> snapshot = dirty;
        if (snapshot.isEmpty()) {
            return EMPTY;
        }
        dirty = ConcurrentHashMap.newKeySet();
        return snapshot.stream().mapToLong(Long::longValue).toArray();
    }

    // ---- pruning (Baritone CachedWorld.prune) ----

    /**
     * Drop every cached section whose horizontal distance from ALL {@code anchors} (companion feet)
     * exceeds {@link #PRUNE_DISTANCE}. Mirrors {@code CachedWorld.prune}: distance-based, XZ only.
     * Empty anchors → keep everything (no live companion to anchor around).
     */
    public void prune(Iterable<BlockPos> anchors) {
        sections.keySet().removeIf(key -> {
            int cx = (SectionPos.x(key) << 4) + 8;
            int cz = (SectionPos.z(key) << 4) + 8;
            boolean near = false;
            for (BlockPos a : anchors) {
                double dx = cx - a.getX();
                double dz = cz - a.getZ();
                if (Math.sqrt(dx * dx + dz * dz) <= PRUNE_DISTANCE) {
                    near = true;
                    break;
                }
            }
            return !near;
        });
    }

    /** Cached section count — for memory accounting / debug overlay. */
    public int sectionCount() {
        return sections.size();
    }

    public void clear() {
        sections.clear();
        dirty.clear();
    }

    private static long sectionKey(int x, int y, int z) {
        return SectionPos.asLong(
                SectionPos.blockToSectionCoord(x),
                SectionPos.blockToSectionCoord(y),
                SectionPos.blockToSectionCoord(z));
    }

    private static final long[] EMPTY = new long[0];
}
