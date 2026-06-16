package com.dwinovo.animus.mixin;

import com.dwinovo.animus.pathing.cache.PathCaches;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every block change on the server funnels through {@link LevelChunk#setBlockState} — the one place to
 * keep the off-thread pathfinding cache fresh (docs/PATHFINDING_ASYNC.md §5.1). We only flag the
 * section dirty; the actual re-encode happens on the main thread end-of-tick ({@link
 * PathCaches#flushDirty}). {@link PathCaches#onBlockChanged} is a no-op unless this level has a cache
 * (a companion is operating here), so an idle server pays a single map lookup per block change.
 *
 * <p><b>Coverage:</b> every {@code Level.setBlock} (player edits, the companion's own mining/building,
 * redstone, pistons, fluids) funnels through here, so companion-relevant changes are caught. A few
 * engine-internal paths write a loaded section directly (post-load worldgen / structures into an
 * already-loaded neighbour, bulk section swaps) and are NOT tracked — the cache can lag live there.
 * That's the same eventual-consistency Baritone accepts; the executor re-costs against the LIVE world
 * and replans on divergence, so a stale search cell self-corrects rather than misleads.
 */
@Mixin(LevelChunk.class)
public class MixinLevelChunk {

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void animus$markPathCacheDirty(BlockPos pos, BlockState state, int flags,
                                           CallbackInfoReturnable<BlockState> cir) {
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (!level.isClientSide()) {
            PathCaches.onBlockChanged(level, pos);
        }
    }
}
