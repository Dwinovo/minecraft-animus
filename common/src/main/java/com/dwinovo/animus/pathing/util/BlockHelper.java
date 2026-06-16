package com.dwinovo.animus.pathing.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
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

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    /**
     * Can the entity's body occupy this cell (no collision, not a fluid we
     * refuse to enter)? True for air, grass, flowers, etc.
     *
     * <p>Water is Baritone's, not a bolt-on swim move: a port of
     * {@code MovementHelper.canWalkThrough}'s fluid branch (jesus off). Flowing
     * water is refused (the current shoves us off-path); still water is passable
     * ONLY as the SURFACE cell — i.e. nothing fluid directly above it — so the
     * move graph contains the water surface plane but never a submerged corridor.
     * Lava is never passable. This is what lets the generic traverse/ascend/
     * descend route across water exactly as Baritone does, with no swim edge.
     */
    public static boolean canWalkThrough(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            if (!fluid.is(FluidTags.WATER)) return false;       // lava etc. — never
            if (isFlowingWater(level, pos)) return false;        // current shoves us
            // Surface only: a fluid (or a lily pad) directly above means we'd be
            // submerged / capped here, not at a free surface (Baritone).
            BlockState up = level.getBlockState(pos.above());
            if (!up.getFluidState().isEmpty()) return false;
            return !up.is(Blocks.LILY_PAD);
        }
        // Baritone canWalkThroughBlockState NO list: blocks we must never path through.
        // Many have an EMPTY/partial collision shape (fire, cobweb, sweet-berry, powder
        // snow, open trapdoor, big dripleaf…) so the raw shape test below would wrongly
        // pass them; the explicit list is what stops the body walking into them.
        if (block instanceof BaseFireBlock
                || state.is(Blocks.COBWEB) || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.COCOA) || block instanceof AbstractSkullBlock
                || state.is(Blocks.BUBBLE_COLUMN) || block instanceof ShulkerBoxBlock
                || block instanceof SlabBlock || block instanceof TrapDoorBlock
                || state.is(Blocks.HONEY_BLOCK) || state.is(Blocks.END_ROD)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POINTED_DRIPSTONE)
                || block instanceof AmethystClusterBlock || block instanceof AzaleaBlock
                || state.is(Blocks.BIG_DRIPLEAF) || state.is(Blocks.POWDER_SNOW)) {
            return false;
        }
        // Wooden doors / fence gates are passable even when shut — the path
        // executor opens them by hand (Baritone's model). Iron doors stay a hard
        // obstruction: no redstone, can't open. So they fall through to the
        // collision test below and read as solid.
        if (isOpenableDoor(state)) {
            return true;
        }
        // Carpet: a thin floor cover, always walkable over (Baritone MAYBE → shallow).
        if (block instanceof CarpetBlock) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
        return shape.isEmpty();
    }

    /**
     * A wooden door or fence gate the body can open by hand — NOT an iron door
     * (needs redstone). The path treats these as passable (no breaking) and the
     * executor right-clicks them open when shut.
     */
    public static boolean isOpenableDoor(BlockState state) {
        if (state.is(Blocks.IRON_DOOR)) {
            return false;
        }
        return state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof FenceGateBlock;
    }

    /** Is this door/gate currently open (OPEN blockstate property true)? */
    public static boolean isDoorOpen(BlockState state) {
        return state.hasProperty(BlockStateProperties.OPEN)
                && state.getValue(BlockStateProperties.OPEN);
    }

    /**
     * Baritone {@code isDoorPassable}/{@code isGatePassable}: can a body approaching the
     * door/gate at {@code doorPos} from the adjacent cell {@code fromPos} pass through it
     * AS IT CURRENTLY STANDS? A fence gate is passable iff open. A door is orientation-aware
     * ({@code isHorizontalBlockPassable}): passable iff {@code (facingAxis == approachAxis) == open}
     * — so an open door perpendicular to the approach still BLOCKS (its panel swung across the
     * gap), and a closed door flush with the approach does not. The executor toggles (right-clicks)
     * any door/gate this reports as NOT passable, which both opens a blocking-closed one and
     * closes a blocking-open one. Non-door/gate blocks read as passable (not our concern).
     */
    public static boolean isDoorwayPassable(BlockGetter level, BlockPos doorPos, BlockPos fromPos) {
        BlockState state = level.getBlockState(doorPos);
        Block block = state.getBlock();
        if (block instanceof FenceGateBlock) {
            return state.getValue(BlockStateProperties.OPEN);
        }
        if (!(block instanceof DoorBlock)) {
            return true;
        }
        if (fromPos.equals(doorPos)) {
            return false;
        }
        Direction.Axis facing = state.getValue(HorizontalDirectionalBlock.FACING).getAxis();
        boolean open = state.getValue(BlockStateProperties.OPEN);
        Direction.Axis approach;
        if (fromPos.north().equals(doorPos) || fromPos.south().equals(doorPos)) {
            approach = Direction.Axis.Z;
        } else if (fromPos.east().equals(doorPos) || fromPos.west().equals(doorPos)) {
            approach = Direction.Axis.X;
        } else {
            return true;   // not cardinally adjacent (diagonal / wrong Y) → don't toggle
        }
        return (facing == approach) == open;
    }

    /** Is this cell water (source or flowing)? */
    public static boolean isWater(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).getFluidState()
                .is(net.minecraft.tags.FluidTags.WATER);
    }

    /**
     * Baritone {@code MovementHelper.mustBeSolidToWalkOn}: "if a move makes us stand
     * on this cell, will it have a top to walk on?" Returns {@code true} for AIR and
     * solid blocks alike — crucially OPTIMISTIC about air, because when bridging the
     * block you stand on was placed by the bridge itself and isn't in the static world
     * snapshot yet. {@code false} only for ladders/vines (you climb, not stand) and
     * submerged water (liquid above). This is what lets a void bridge CHAIN: each
     * sneak-backplace is allowed against the (about-to-be-placed) block below.
     */
    public static boolean mustBeSolidToWalkOn(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.LADDER) || state.is(Blocks.VINE)) return false;
        if (!state.getFluidState().isEmpty()) {
            // standing on water counts only at the surface (nothing fluid above), jesus off
            return level.getBlockState(pos.above()).getFluidState().isEmpty();
        }
        return true;   // air or solid → optimistically a valid floor to backplace from
    }

    /**
     * Is this a FLOWING water cell — Baritone {@code MovementHelper.isFlowing}?
     * A non-source level is flowing; a source block is "flowing" too when it
     * feeds a horizontal neighbour (edge of a pool), which Baritone treats as
     * unsafe to walk because the current there pushes you. Used to keep the
     * route on still water only.
     */
    public static boolean isFlowingWater(BlockGetter level, BlockPos pos) {
        FluidState fluid = level.getBlockState(pos).getFluidState();
        if (!fluid.is(FluidTags.WATER)) return false;
        if (!fluid.isSource()) return true;                 // amount < 8 → flowing
        for (Direction d : HORIZONTAL) {
            FluidState n = level.getBlockState(pos.relative(d)).getFluidState();
            if (n.is(FluidTags.WATER) && !n.isSource()) return true;
        }
        return false;
    }

    /**
     * Can the entity stand on TOP of this block (i.e. is it a solid floor)?
     *
     * <p>Water mirrors Baritone {@code canWalkOnPosition} (jesus off): a water
     * cell is "walkable on" iff there is water directly ABOVE it — i.e. you
     * float at the surface, treading on the submerged column, never on the very
     * top (air-headed) cell. Combined with {@link #canWalkThrough}'s surface
     * rule, this pins the body to the water surface plane just like Baritone.
     */
    public static boolean canWalkOn(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            if (!fluid.is(FluidTags.WATER)) return false;   // lava (assumeWalkOnLava off)
            // Walk on water only where water is above (submerged → float here).
            return isWater(level, pos.above());
        }
        if (state.isAir()) return false;
        // Baritone canWalkOnBlockState allow-list: blocks that are NOT full collision
        // cubes but are still safe to stand on. The generic full-cube test below would
        // miss these (farmland/path are 15/16 tall, chests/ladders/azalea aren't cubes).
        if (state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.SOUL_SAND)) return true;
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.ENDER_CHEST)) return true;
        if (state.is(Blocks.GLASS) || state.getBlock() instanceof StainedGlassBlock) return true;
        if (state.is(Blocks.LADDER)) return true;
        if (state.getBlock() instanceof AzaleaBlock) return true;
        if (state.getBlock() instanceof StairBlock) return true;
        if (state.getBlock() instanceof SlabBlock) {
            // Baritone default allowWalkOnBottomSlab=true → ALL slabs are a floor. Standing
            // on a bottom slab is reconciled by playerFeet() returning the cell ABOVE it.
            return true;
        }
        // Magma / honey are full cubes but Baritone refuses them (damage / stickiness).
        if (state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.HONEY_BLOCK)) return false;
        // Everything else: a normal full collision cube (Baritone isBlockNormalCube).
        return state.isCollisionShapeFullBlock(level, pos);
    }

    /**
     * Baritone {@code IPlayerContext.playerFeet}: the body's feet cell for pathing — the
     * position nudged up 0.1251 (so sinking on soul sand / farmland doesn't read a block
     * low) and, when that cell is a SLAB, taken as the cell ABOVE it. The slab adjustment
     * is what reconciles standing on a bottom slab (feet at slab.y+0.5) with the move graph,
     * where a move onto a slab targets the cell ABOVE the slab.
     */
    public static BlockPos playerFeet(BlockGetter level, double x, double y, double z) {
        BlockPos f = BlockPos.containing(x, y + 0.1251, z);
        if (level.getBlockState(f).getBlock() instanceof SlabBlock) {
            return f.above();
        }
        return f;
    }

    /** Baritone MovementHelper.isBottomSlab. */
    public static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    public static boolean isBottomSlab(BlockGetter level, BlockPos pos) {
        return isBottomSlab(level.getBlockState(pos));
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
        // The non-fluid members of Baritone's avoidWalkingInto — blocks we must never
        // path into (magma is avoided at default allowWalkOnMagmaBlocks=false).
        return state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.getBlock() instanceof BaseFireBlock
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.BUBBLE_COLUMN);
    }

    /**
     * Baritone {@code MovementHelper.avoidWalkingInto}: a cell we shouldn't walk/sprint
     * INTO — ANY fluid (incl. water, unlike {@link #isHazard}) plus the same block set.
     * Used for "is it safe to keep moving into the cell ahead" checks (walk-while-break
     * suppressor, descend safeMode), where even water counts (the current shoves us).
     */
    public static boolean avoidWalkingInto(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return true;
        return state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.getBlock() instanceof BaseFireBlock
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.BUBBLE_COLUMN);
    }

    /**
     * Baritone {@code isBlockNormalCube}: a full-collision cube, EXCLUDING the handful of
     * full-shape blocks you can't reliably look-at-and-place-against (bamboo, a moving
     * piston, scaffolding, shulker boxes, pointed dripstone, amethyst clusters).
     */
    public static boolean isBlockNormalCube(BlockGetter level, BlockPos pos, BlockState state) {
        Block b = state.getBlock();
        if (b instanceof BambooStalkBlock || b instanceof MovingPistonBlock
                || b instanceof ScaffoldingBlock || b instanceof ShulkerBoxBlock
                || b instanceof PointedDripstoneBlock || b instanceof AmethystClusterBlock) {
            return false;
        }
        return state.isCollisionShapeFullBlock(level, pos);
    }

    /**
     * Baritone {@code canPlaceAgainst}: can we look at the centre of a side face of this
     * block and likely place against it — a normal cube, plain glass, or stained glass
     * (NOT every full-collision block: carpets, shulkers, scaffolding etc. are refused).
     */
    public static boolean canPlaceAgainst(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return isBlockNormalCube(level, pos, state)
                || state.is(Blocks.GLASS) || state.getBlock() instanceof StainedGlassBlock;
    }

    /**
     * The neighbour direction whose fluid would pour into {@code pos} if it
     * were broken — {@link Direction#UP} or a horizontal, never {@code DOWN}
     * (vanilla fluids spread to the cardinals + down, so only an overhead or
     * sideways source can fill a cell you just emptied; a fluid below can't
     * flow up into it). Lava is returned in preference to water as the worse
     * hazard. Returns {@code null} when breaking releases no flow.
     *
     * <p>The single source of truth for "breaking this unleashes a fluid",
     * shared by the A* break cost ({@link #breakWouldCreateFlow}) and the
     * miner's teach gate ({@code BlockMiningProgress.fluidBreakHazard}) so
     * "safe to route through" and "safe to mine on purpose" never disagree.
     * Mirrors mineflayer's {@code dontCreateFlow} neighbour set.
     */
    public static Direction fluidReleasedByBreaking(BlockGetter level, BlockPos pos) {
        Direction water = null;
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            FluidState fluid = level.getBlockState(pos.relative(dir)).getFluidState();
            if (fluid.isEmpty()) continue;
            if (fluid.is(FluidTags.LAVA)) return dir;   // worst hazard wins
            if (water == null) water = dir;
        }
        return water;
    }

    /**
     * Boolean form of {@link #fluidReleasedByBreaking} for the A* break cost:
     * would breaking {@code pos} expose the cell to an adjacent fluid that
     * then floods or lava-bathes the route?
     */
    public static boolean breakWouldCreateFlow(BlockGetter level, BlockPos pos) {
        return fluidReleasedByBreaking(level, pos) != null;
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

    /**
     * Can {@code inv}'s tools actually HARVEST {@code state}'s drops — i.e. break it and get the
     * item, not just destroy it? True when the block drops without a tool, or some hotbar slot
     * (0-8, the quick-switchable set Baritone's ToolSet uses) holds the correct tool. Mining a
     * {@code requiresCorrectToolForDrops} block with the wrong tool removes it for nothing, so
     * the cost model vetoes it and the break/mine tools refuse it. Single source of truth.
     */
    public static boolean canHarvest(Container inv, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        int hotbar = Math.min(9, inv.getContainerSize());
        for (int i = 0; i < hotbar; i++) {
            if (inv.getItem(i).isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
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
