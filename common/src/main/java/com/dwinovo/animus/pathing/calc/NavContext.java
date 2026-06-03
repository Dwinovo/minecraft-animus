package com.dwinovo.animus.pathing.calc;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.init.InitTag;
import com.dwinovo.animus.pathing.util.ActionCosts;
import com.dwinovo.animus.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
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
    public final AnimusEntity entity;

    /** True if the entity holds at least one scaffolding block. */
    public final boolean hasScaffold;

    /** Max blocks the entity may fall without taking dangerous damage. */
    public final int maxFallHeight;

    /** Held main-hand tool snapshot, for tool-aware mining duration. */
    private final ItemStack tool;

    public NavContext(AnimusEntity entity) {
        this.entity = entity;
        this.level = entity.level();
        this.tool = entity.getMainHandItem().copy();

        this.hasScaffold = hasAnyScaffold(entity.getInventory());

        // Survivable fall: vanilla fall damage starts at 3.5 blocks (1 block
        // damage at 4). Cap conservatively at 3 so the bot never hurts itself.
        this.maxFallHeight = 3;
    }

    private static boolean hasAnyScaffold(SimpleContainer inv) {
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
        if (!BlockHelper.isReplaceableForPlacement(level, pos)) return ActionCosts.COST_INF;
        if (BlockHelper.isHazard(level, pos)) return ActionCosts.COST_INF;
        return ActionCosts.PLACE_BLOCK;
    }

    /**
     * Cost of breaking the block at {@code pos}: tool-aware mining duration in
     * ticks plus {@link ActionCosts#BREAK_ADDITIONAL}. {@code COST_INF} when
     * unbreakable, a hazard, or breaking would unleash a fluid flow.
     */
    public double costOfBreaking(BlockPos pos) {
        if (!BlockHelper.isBreakable(level, pos)) return ActionCosts.COST_INF;
        if (BlockHelper.isHazard(level, pos)) return ActionCosts.COST_INF;
        if (BlockHelper.breakWouldCreateFlow(level, pos)) return ActionCosts.COST_INF;
        return miningTicks(pos) + ActionCosts.BREAK_ADDITIONAL;
    }

    /**
     * Tool-aware mining duration in ticks, replicating the formula in
     * {@code BlockMiningProgress.tryStart} so search cost matches execution
     * reality.
     */
    public double miningTicks(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness <= 0.0f) return 1.0;
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        float toolSpeed = tool.getDestroySpeed(state);
        if (toolSpeed <= 0.0f) toolSpeed = 1.0f;
        float divisor = correct ? 30.0f : 100.0f;
        return Math.max(1.0, Math.ceil(hardness * divisor / toolSpeed));
    }

    /** Pick a scaffolding stack the entity currently holds, or null. */
    public static ItemStack firstScaffoldItem(SimpleContainer inv) {
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
