package com.dwinovo.tulpa.pathing.calc;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * A read-through, memoizing world view for one A* search — Baritone's
 * {@code BlockStateInterface} role. A search re-queries the same cells many
 * times across overlapping neighbour generations; caching {@link #getBlockState}
 * turns those repeated chunk lookups (and the collision-shape work they feed)
 * into one fetch per cell.
 *
 * <p>Because it implements {@link BlockGetter}, the existing
 * {@link com.dwinovo.tulpa.pathing.util.BlockHelper} predicates (which already
 * take a {@code BlockGetter}) read through it unchanged — no duplicated
 * classification logic. Lifetime is a single {@link NavContext}/search, during
 * which the executor isn't mutating the world, so the cache is a consistent
 * snapshot for that search; a fresh search (replan) builds a fresh one.
 */
public final class NavSnapshot implements BlockGetter, com.dwinovo.tulpa.pathing.util.BlockEntityAware {

    private final Level level;
    private final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();

    public NavSnapshot(Level level) {
        this.level = level;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        long key = pos.asLong();
        BlockState cached = states.get(key);
        if (cached != null) {
            return cached;
        }
        BlockState state = level.getBlockState(pos);
        states.put(key, state);
        return state;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        // Rare (only the don't-grief check); not worth caching, delegate live.
        return level.getBlockEntity(pos);
    }

    @Override
    public boolean hasBlockEntity(BlockPos pos) {
        return level.getBlockEntity(pos) != null;   // live view (main thread)
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return level.getMinBuildHeight();
    }
}
