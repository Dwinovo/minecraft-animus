package com.dwinovo.animus.pathing.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Stateless terrain predicates used by the movement primitives and the A*
 * search. Mirrors the role of Baritone's {@code MovementHelper}: decide
 * whether a block can be stood on, passed through, broken, or is a hazard —
 * without any entity instance, just a {@link BlockGetter} + {@link BlockPos}.
 *
 * <h2>Coordinate convention</h2>
 * A "feet position" {@code p} is walkable as a standing spot when:
 * <ul>
 *   <li>{@code p} and {@code p.above()} are pass-through (air/non-colliding) —
 *       room for the 2-tall entity body, and</li>
 *   <li>{@code p.below()} is solid-walkable — something to stand on.</li>
 * </ul>
 */
public final class BlockHelper {

    private BlockHelper() {}

    /**
     * Can the entity's body occupy this cell (no collision, not a fluid we
     * refuse to enter)? True for air, grass, flowers, etc.
     */
    public static boolean canWalkThrough(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            // Treat any fluid cell as non-pass-through for the MVP — we don't
            // swim or wade. (Water support can be added later via a flag.)
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
        return shape.isEmpty();
    }

    /**
     * Can the entity stand on TOP of this block (i.e. is it a solid floor)?
     * Requires a full-ish top face and no fluid.
     */
    public static boolean canWalkOn(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return false;
        if (state.isAir()) return false;
        VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
        if (shape.isEmpty()) return false;
        // Top face must reach y=1 (full-height collision) so the entity doesn't
        // sink into slabs/fences. max(Y) of the shape's bounds == 1.0.
        return shape.max(net.minecraft.core.Direction.Axis.Y) >= 1.0;
    }

    /**
     * A "feet" cell is a valid standing spot: 2 cells of clearance above a
     * solid floor.
     */
    public static boolean isStandable(BlockGetter level, BlockPos feet) {
        return canWalkOn(level, feet.below())
                && canWalkThrough(level, feet)
                && canWalkThrough(level, feet.above());
    }

    /**
     * Is this block a hazard the bot must never stand in / next to break?
     * Lava and fire are hard hazards; we keep it minimal for the MVP.
     */
    public static boolean isHazard(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            // Lava is the dangerous fluid. Water we simply don't enter (above),
            // but it isn't a damage hazard.
            return fluid.getType().getBucket() == net.minecraft.world.item.Items.LAVA_BUCKET;
        }
        return false;
    }

    /**
     * Would breaking {@code pos} expose the cell to an adjacent fluid that
     * would then flow into the entity's path? Mirrors mineflayer's
     * {@code dontCreateFlow} — refuse to mine blocks touching liquid so the
     * bot doesn't flood or lava-bathe itself.
     */
    public static boolean breakWouldCreateFlow(BlockGetter level, BlockPos pos) {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            if (dir == net.minecraft.core.Direction.DOWN) continue;
            BlockState neighbor = level.getBlockState(pos.relative(dir));
            if (!neighbor.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /**
     * Is this block breakable at all? Bedrock / unbreakable (hardness < 0) are
     * never breakable; air is a no-op (return false — nothing to break).
     */
    public static boolean isBreakable(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        float hardness = state.getDestroySpeed(asBlockGetterLevel(level), pos);
        return hardness >= 0.0f;
    }

    /**
     * {@code getDestroySpeed} takes a {@code BlockGetter}; this is just an
     * identity pass-through kept as a seam in case we need to adapt the
     * argument type per loader/version.
     */
    private static BlockGetter asBlockGetterLevel(BlockGetter level) {
        return level;
    }

    /** Convenience: full-block solid we are happy to place scaffolding against. */
    public static boolean isReplaceableForPlacement(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    /**
     * A block the bot must never destroy while pathing: any block-entity
     * (chests, furnaces, hoppers, barrels, shulker boxes, spawners, beacons,
     * lecterns, …) or a bed. These are player-placed functional/valuable blocks —
     * route around them, don't grief. Mirrors Baritone's
     * {@code blocksToAvoidBreaking}; the {@code BlockEntity != null} test is a
     * cheap, broad proxy that catches essentially every griefable block.
     */
    public static boolean shouldAvoidBreaking(BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) != null) return true;
        return level.getBlockState(pos).getBlock() instanceof BedBlock;
    }

    /**
     * Would breaking {@code pos} drop a {@link FallingBlock} (sand / gravel /
     * anvil / concrete powder) sitting directly above it onto the bot? Refuse so
     * we never bury or suffocate ourselves. Mirrors mineflayer's
     * {@code dontMineUnderFallingBlock}.
     */
    public static boolean breakReleasesFallingBlock(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos.above()).getBlock() instanceof FallingBlock;
    }
}
