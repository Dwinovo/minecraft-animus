package com.dwinovo.animus.pathing.calc;

import com.dwinovo.animus.init.InitTag;
import com.dwinovo.animus.pathing.util.ActionCosts;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.pathing.util.PathSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-search snapshot binding the world, the entity's capabilities, and a
 * frozen view of its scaffolding inventory + held tool. Mirrors Baritone's
 * {@code CalculationContext}: every {@link com.cost}-returning helper reads
 * only from this immutable snapshot, so an A* search is deterministic and
 * doesn't see mid-search inventory mutation.
 *
 * <h2>Inventory gating (the key behaviour)</h2>
 * {@link #hasScaffold} is a boolean: does the entity hold ANY block in the
 * {@link InitTag#SCAFFOLDS} tag? When false, {@link #costOfPlacing} returns
 * {@link ActionCosts#COST_INF} for every position, so all bridge/step-up
 * moves become impossible and A* routes around (or fails with "out of
 * material"). Actual depletion is handled at execution time — when the
 * executor runs the inventory dry mid-path it triggers a replan, and the
 * fresh snapshot then has {@code hasScaffold == false}. This is the
 * validated Baritone/mineflayer model (boolean gate + event-driven replan),
 * avoiding an explosive per-node "remaining blocks" search state.
 */
public final class NavContext {

    public final Level level;

    /**
     * Memoizing read-through view of {@link #level} for this search. All
     * terrain queries (here and in {@link com.dwinovo.animus.pathing.movement.Moves})
     * go through it so each cell is fetched once per search.
     */
    public final NavSnapshot view;

    /** True if the entity holds at least one scaffolding block. */
    public final boolean hasScaffold;

    /** Max blocks the entity may fall without taking dangerous damage. */
    public final int maxFallHeight;

    /** Held main-hand tool snapshot, for tool-aware mining duration. */
    private final ItemStack tool;

    /**
     * Snapshot the world + the body's capabilities for one A* search. Decoupled
     * from any specific entity type: callers pass the level, a copy of the held
     * tool, and the inventory to gate scaffolding on (a {@link Container}, so a
     * Mob's {@code SimpleContainer} or a player's {@code Inventory} both work).
     */
    public NavContext(Level level, ItemStack mainHandTool, Container inventory) {
        this.level = level;
        this.view = new NavSnapshot(level);
        this.tool = mainHandTool.copy();
        this.hasScaffold = hasAnyScaffold(inventory);

        // Survivable fall: Baritone's maxFallHeightNoWater (3) — vanilla fall
        // damage starts at 3.5 blocks; cap conservatively so the bot never hurts itself.
        this.maxFallHeight = PathSettings.MAX_FALL_HEIGHT_NO_WATER;
    }

    private static boolean hasAnyScaffold(Container inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isScaffold(inv.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cost of placing a scaffolding block at {@code pos} (to bridge / step on).
     * {@link ActionCosts#COST_INF} when the entity has no scaffolding, the
     * cell isn't replaceable, or placing there would touch a hazard.
     */
    public double costOfPlacing(BlockPos pos) {
        if (!hasScaffold) return ActionCosts.COST_INF;
        if (!BlockHelper.isReplaceableForPlacement(view, pos)) return ActionCosts.COST_INF;
        if (BlockHelper.isHazard(view, pos)) return ActionCosts.COST_INF;
        return PathSettings.BLOCK_PLACEMENT_PENALTY;
    }

    /**
     * Cost of breaking the block at {@code pos}: tool-aware mining duration in
     * ticks plus {@link PathSettings#BLOCK_BREAK_ADDITIONAL_PENALTY}. {@code COST_INF} (→ A*
     * routes around, or the search fails clean) when the break is impossible,
     * unsafe, or ineffective:
     * <ul>
     *   <li>unbreakable (bedrock/barrier), a hazard (lava/fire), or would unleash
     *       a fluid flow;</li>
     *   <li>{@link BlockHelper#shouldAvoidBreaking} — a player-placed functional
     *       block (chest, furnace, bed, …): don't grief it;</li>
     *   <li>{@link BlockHelper#breakReleasesFallingBlock} — sand/gravel above
     *       would cave in on the bot;</li>
     *   <li><b>ineffective break</b> — the block needs the correct tool for drops
     *       and the entity's held tool isn't it. We refuse the slow, drop-less
     *       bare-hand/wrong-tool grind: the bot only digs through what its current
     *       tool handles effectively (dirt/wood/sand bare-handed are fine — those
     *       need no tool). To tunnel through stone/ore it must equip a proper
     *       pickaxe first, same lesson as {@code auto_mine}.</li>
     * </ul>
     */
    public double costOfBreaking(BlockPos pos) {
        if (!BlockHelper.isBreakable(view, pos)) return ActionCosts.COST_INF;
        if (BlockHelper.isHazard(view, pos)) return ActionCosts.COST_INF;
        if (BlockHelper.breakWouldCreateFlow(view, pos)) return ActionCosts.COST_INF;
        if (BlockHelper.shouldAvoidBreaking(view, pos)) return ActionCosts.COST_INF;
        if (BlockHelper.breakReleasesFallingBlock(view, pos)) return ActionCosts.COST_INF;

        BlockState state = view.getBlockState(pos);
        if (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state)) {
            return ActionCosts.COST_INF;   // ineffective with the held tool — route around
        }
        return miningTicks(pos) + PathSettings.BLOCK_BREAK_ADDITIONAL_PENALTY;
    }

    /**
     * Tool-aware mining duration in ticks, replicating the formula in
     * {@code BlockMiningProgress.tryStart} so search cost matches execution
     * reality.
     */
    public double miningTicks(BlockPos pos) {
        BlockState state = view.getBlockState(pos);
        float hardness = state.getDestroySpeed(view, pos);
        if (hardness <= 0.0f) return 1.0;
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        float toolSpeed = tool.getDestroySpeed(state);
        if (toolSpeed <= 0.0f) toolSpeed = 1.0f;
        float divisor = correct ? 30.0f : 100.0f;
        return Math.max(1.0, Math.ceil(hardness * divisor / toolSpeed));
    }

    /**
     * Post-mortem for a failed search: walk the straight start→goal line and
     * name the first break-veto on it, in words the LLM can act on. The real
     * A* frontier may have died elsewhere, but the straight line is what the
     * model pictures when it asks "why can't you just go there" — "stone but
     * I'm holding a sword" or "water behind it" turns a dead "obstructed"
     * into a next step. Returns {@code null} when the line is clean (the
     * failure was geometric: gaps, fall limits, search budget).
     */
    public String diagnoseObstruction(BlockPos from, BlockPos to) {
        int steps = (int) Math.ceil(Math.sqrt(from.distSqr(to)));
        BlockPos last = null;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            BlockPos feet = BlockPos.containing(
                    from.getX() + 0.5 + (to.getX() - from.getX()) * t,
                    from.getY() + (to.getY() - from.getY()) * t,
                    from.getZ() + 0.5 + (to.getZ() - from.getZ()) * t);
            if (feet.equals(last)) continue;
            last = feet;
            for (BlockPos cell : new BlockPos[]{feet, feet.above()}) {
                if (BlockHelper.canWalkThrough(view, cell)) continue;
                String veto = explainBreakVeto(cell);
                if (veto != null) {
                    return veto + " at (" + cell.getX() + ", " + cell.getY()
                            + ", " + cell.getZ() + ")";
                }
            }
        }
        return null;
    }

    /**
     * Why {@link #costOfBreaking} returns {@code COST_INF} for this cell, as a
     * sentence fragment for the LLM — or {@code null} if the break is actually
     * allowed (finite cost). Mirrors the veto order in {@code costOfBreaking}.
     */
    private String explainBreakVeto(BlockPos pos) {
        BlockState state = view.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().is(FluidTags.LAVA)
                    ? "lava" : "water (I can't swim or mine through fluids)";
        }
        if (!BlockHelper.isBreakable(view, pos)) {
            return "unbreakable " + blockId(state);
        }
        if (BlockHelper.isHazard(view, pos)) {
            return "hazardous " + blockId(state);
        }
        if (BlockHelper.breakWouldCreateFlow(view, pos)) {
            return blockId(state) + " (breaking it would release "
                    + (touchesLava(pos) ? "lava" : "water") + " from an adjacent cell)";
        }
        if (BlockHelper.shouldAvoidBreaking(view, pos)) {
            return blockId(state) + " (a functional block I won't destroy)";
        }
        if (BlockHelper.breakReleasesFallingBlock(view, pos)) {
            return blockId(state) + " (sand/gravel above it would collapse on me)";
        }
        if (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state)) {
            String held = tool.isEmpty()
                    ? "nothing"
                    : BuiltInRegistries.ITEM.getKey(tool.getItem()).getPath();
            return blockId(state) + " (needs the correct tool and I'm holding "
                    + held + " — equip_item the right pickaxe first)";
        }
        return null;
    }

    private boolean touchesLava(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            if (view.getBlockState(pos.relative(dir)).getFluidState().is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /** Pick a scaffolding stack the entity currently holds, or null. */
    public static ItemStack firstScaffoldItem(Container inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (isScaffold(s)) {
                return s;
            }
        }
        return null;
    }

    /**
     * True if {@code stack} is a placeable scaffolding block: a {@link BlockItem}
     * tagged {@link InitTag#SCAFFOLDS}. The {@code BlockItem} check guarantees
     * the executor can actually place it even if a pack tags an odd item.
     */
    public static boolean isScaffold(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem
                && stack.is(InitTag.SCAFFOLDS);
    }
}
