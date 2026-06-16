package com.dwinovo.animus.pathing.cache;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable, compressed copy of one 16×16×16 chunk section's block states — the cache unit for
 * off-thread pathfinding (see {@code docs/PATHFINDING_ASYNC.md} §4). A palette of the section's
 * distinct {@link BlockState}s (interned singletons — we store references, not copies) plus a
 * bit-packed index per cell ({@code ceil(log2(palette))} bits). Everything pathfinding doesn't need
 * — biomes, block/sky light, vanilla {@link net.minecraft.world.level.chunk.PalettedContainer}'s
 * lock/listener/resize machinery — is dropped.
 *
 * <p><b>Why a copy, not a live read</b> (diverges from Baritone, which reads loaded chunks live
 * off-thread): a {@code BlockState} is frozen at registry init and never mutates, so reading our
 * palette+indices off a worker thread is race-free. The backing arrays are never written after
 * construction; a block change rebuilds a fresh {@code CompactSection} and atomically swaps it in
 * the {@link SectionCache}. The richer cost model needs real {@code BlockState} (hardness, tool,
 * fluid), so we keep identity instead of Baritone's lossy 2-bit pack.
 */
public final class CompactSection {

    private static final int SIZE = 16 * 16 * 16;

    private final BlockState[] palette;
    private final SimpleBitStorage indices;

    private CompactSection(BlockState[] palette, SimpleBitStorage indices) {
        this.palette = palette;
        this.indices = indices;
    }

    /** A 16³ block-state source — lets {@link #encode} be driven by a live section or, in tests, a
     *  plain lambda, without depending on a real {@link LevelChunkSection}. */
    @FunctionalInterface
    public interface StateSource {
        BlockState at(int x, int y, int z);
    }

    /** Encode from a live chunk section (call on the main thread — reads its palette container). */
    public static CompactSection encode(LevelChunkSection section) {
        return encode(section::getBlockState);
    }

    /** Encode from any 16³ state source. Builds the distinct-state palette and bit-packs an index
     *  per cell. Reference identity is enough (states are interned) and faster than {@code equals}. */
    public static CompactSection encode(StateSource src) {
        Reference2IntOpenHashMap<BlockState> lookup = new Reference2IntOpenHashMap<>();
        lookup.defaultReturnValue(-1);
        List<BlockState> palette = new ArrayList<>();
        int[] idx = new int[SIZE];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = src.at(x, y, z);
                    int pi = lookup.getInt(state);
                    if (pi < 0) {
                        pi = palette.size();
                        palette.add(state);
                        lookup.put(state, pi);
                    }
                    idx[index(x, y, z)] = pi;
                }
            }
        }
        SimpleBitStorage indices = new SimpleBitStorage(bitsFor(palette.size()), SIZE);
        for (int i = 0; i < SIZE; i++) {
            indices.set(i, idx[i]);
        }
        return new CompactSection(palette.toArray(new BlockState[0]), indices);
    }

    /** Block state at section-local {@code (lx,ly,lz)} (each 0..15). Race-free off-thread. */
    public BlockState get(int lx, int ly, int lz) {
        return palette[indices.get(index(lx, ly, lz))];
    }

    /** Distinct states in this section (palette size) — for tests / memory accounting. */
    public int paletteSize() {
        return palette.length;
    }

    /** Bits needed to index a palette of {@code paletteSize} entries: {@code ceil(log2(size))}, but at
     *  least 1 ({@link SimpleBitStorage} requires ≥1, and a uniform section still needs index 0). */
    static int bitsFor(int paletteSize) {
        return paletteSize <= 1 ? 1 : 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    /** Vanilla section index order ({@code y<<8 | z<<4 | x}) — cache-friendly, matches the container. */
    static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }
}
