package com.dwinovo.animus.task.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Shared mining-policy helpers for the companion's dig tasks. The companion
 * body breaks blocks natively ({@code player.gameMode.destroyBlock}, vanilla
 * timing/drops/durability), so this class no longer runs a per-tick swing loop
 * — it only holds the two policy constants/checks the task layer still shares:
 * the player interaction reach and the fluid-safety gate.
 */
public final class BlockMiningProgress {

    private BlockMiningProgress() {}

    /** Vanilla {@code block_interaction_range} for players is 4.5. */
    public static final double REACH_SQR = 4.5 * 4.5;

    /**
     * Physical-safety gate for a deliberate dig: would breaking the block at
     * {@code pos} unleash a fluid into the worksite? Returns {@code null} when
     * safe, otherwise a teachable reason naming the fluid and what to do.
     *
     * <p>This is the task-layer twin of the pathfinder's break cost: both
     * consult {@link com.dwinovo.animus.pathing.util.BlockHelper#fluidReleasedByBreaking}
     * (UP + the four horizontals — vanilla's flood directions) so "safe to
     * route through" and "safe to mine on purpose" can never disagree. We
     * refuse the dig whole rather than dive, dam, or race the flow — mirroring
     * mineflayer's {@code dontCreateFlow} and Baritone's {@code COST_INF}:
     * there is no finite price that correctly values flooding the worksite or
     * bathing in lava. Water teaches "drain it"; lava teaches "leave it".
     * A fully-submerged block trips this too (water on every face), so it
     * subsumes the old submerged-only check.
     */
    public static String fluidBreakHazard(Level level, BlockPos pos) {
        net.minecraft.core.Direction dir =
                com.dwinovo.animus.pathing.util.BlockHelper.fluidReleasedByBreaking(level, pos);
        if (dir == null) return null;
        boolean lava = level.getBlockState(pos.relative(dir)).getFluidState()
                .is(net.minecraft.tags.FluidTags.LAVA);
        String where = dir == net.minecraft.core.Direction.UP ? "directly above" : "right beside";
        return lava
                ? "won't mine here — LAVA sits " + where + " this block; breaking it "
                        + "would pour lava into the dig. Pick a target away from lava."
                : "won't mine here — water sits " + where + " this block; breaking it "
                        + "would flood the dig. Scoop the water with use_item(bucket) or "
                        + "wall it off first, then mine.";
    }
}
