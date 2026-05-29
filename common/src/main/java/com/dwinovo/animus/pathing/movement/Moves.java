package com.dwinovo.animus.pathing.movement;

import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.util.ActionCosts;
import com.dwinovo.animus.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the candidate {@link Movement}s reachable from a feet-position,
 * each self-costed against the {@link NavContext} snapshot. Infeasible
 * movements (no scaffolding to bridge, unbreakable obstruction, hazard,
 * fall too deep) are simply not emitted.
 *
 * <h2>Primitive set (phase 1)</h2>
 * <ul>
 *   <li><b>Traverse</b> — same-Y step; bridges gaps by placing a floor block
 *       and mines head/feet obstructions.</li>
 *   <li><b>Ascend</b> — step up one block; mines head-room and may place the
 *       step block to climb a ledge that isn't there.</li>
 *   <li><b>Descend / Fall</b> — step out and drop to the first safe floor
 *       within {@link NavContext#maxFallHeight}.</li>
 * </ul>
 * Pillar / Parkour / Diagonal are intentionally deferred.
 */
public final class Moves {

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private Moves() {}

    /** All feasible movements out of {@code from}. */
    public static List<Movement> generate(NavContext ctx, BlockPos from) {
        List<Movement> out = new ArrayList<>(12);
        for (Direction dir : HORIZONTAL) {
            Movement t = traverse(ctx, from, dir);
            if (t != null) out.add(t);
            Movement a = ascend(ctx, from, dir);
            if (a != null) out.add(a);
            Movement d = descend(ctx, from, dir);
            if (d != null) out.add(d);
        }
        return out;
    }

    // ---- Traverse: same-Y step, bridge gaps, dig obstructions ----

    private static Movement traverse(NavContext ctx, BlockPos from, Direction dir) {
        Level level = ctx.level;
        BlockPos dest = from.relative(dir);
        BlockPos head = dest.above();

        double cost = ActionCosts.WALK_ONE_BLOCK;
        List<BlockPos> toBreak = new ArrayList<>(2);

        // Clear the two body cells at the destination.
        double feetBreak = clearCost(ctx, dest, toBreak);
        if (feetBreak >= ActionCosts.COST_INF) return null;
        cost += feetBreak;
        double headBreak = clearCost(ctx, head, toBreak);
        if (headBreak >= ActionCosts.COST_INF) return null;
        cost += headBreak;

        // Floor under the destination.
        BlockPos floor = dest.below();
        BlockPos toPlace = null;
        if (!BlockHelper.canWalkOn(level, floor)) {
            double placeCost = ctx.costOfPlacing(floor);
            if (placeCost >= ActionCosts.COST_INF) return null;
            cost += placeCost;
            toPlace = floor;
        }

        if (BlockHelper.isHazard(level, dest) || BlockHelper.isHazard(level, head)) return null;
        return new Movement(Movement.Kind.TRAVERSE, dest, cost, toBreak, toPlace);
    }

    // ---- Ascend: up one block, dig head-room, place step if needed ----

    private static Movement ascend(NavContext ctx, BlockPos from, Direction dir) {
        Level level = ctx.level;
        BlockPos dest = from.relative(dir).above(); // feet one up & over
        BlockPos destHead = dest.above();
        BlockPos jumpRoom = from.above(2);          // room to jump at the source column

        double cost = ActionCosts.WALK_ONE_BLOCK + ActionCosts.JUMP_ONE_BLOCK;
        List<BlockPos> toBreak = new ArrayList<>(3);

        double jumpBreak = clearCost(ctx, jumpRoom, toBreak);
        if (jumpBreak >= ActionCosts.COST_INF) return null;
        cost += jumpBreak;

        double feetBreak = clearCost(ctx, dest, toBreak);
        if (feetBreak >= ActionCosts.COST_INF) return null;
        cost += feetBreak;
        double headBreak = clearCost(ctx, destHead, toBreak);
        if (headBreak >= ActionCosts.COST_INF) return null;
        cost += headBreak;

        // The block we stand ON after the step (floor under dest).
        BlockPos step = dest.below(); // == from.relative(dir)
        BlockPos toPlace = null;
        if (!BlockHelper.canWalkOn(level, step)) {
            double placeCost = ctx.costOfPlacing(step);
            if (placeCost >= ActionCosts.COST_INF) return null;
            cost += placeCost;
            toPlace = step;
        }

        if (BlockHelper.isHazard(level, dest) || BlockHelper.isHazard(level, destHead)) return null;
        return new Movement(Movement.Kind.ASCEND, dest, cost, toBreak, toPlace);
    }

    // ---- Descend / Fall: step out and drop to first safe floor ----

    private static Movement descend(NavContext ctx, BlockPos from, Direction dir) {
        Level level = ctx.level;
        BlockPos col = from.relative(dir); // horizontal cell we step into

        double cost = ActionCosts.WALK_ONE_BLOCK;
        List<BlockPos> toBreak = new ArrayList<>(2);

        // Clear the two body cells of the column we step into at source height.
        double feetBreak = clearCost(ctx, col, toBreak);
        if (feetBreak >= ActionCosts.COST_INF) return null;
        cost += feetBreak;
        double headBreak = clearCost(ctx, col.above(), toBreak);
        if (headBreak >= ActionCosts.COST_INF) return null;
        cost += headBreak;

        // Scan downward for the first solid floor within the fall cap.
        for (int drop = 1; drop <= ctx.maxFallHeight; drop++) {
            BlockPos feet = col.below(drop);
            BlockPos floor = feet.below();
            if (BlockHelper.canWalkOn(level, floor)
                    && BlockHelper.canWalkThrough(level, feet)
                    && BlockHelper.canWalkThrough(level, feet.above())) {
                if (BlockHelper.isHazard(level, floor) || BlockHelper.isHazard(level, feet)) {
                    return null;
                }
                double total = cost + ActionCosts.fallCost(drop);
                Movement.Kind kind = drop == 1 ? Movement.Kind.DESCEND : Movement.Kind.FALL;
                return new Movement(kind, feet, total, toBreak, null);
            }
            // If the cell we'd pass through is itself an obstruction we can't
            // clear, stop scanning this column.
            if (!BlockHelper.canWalkThrough(level, feet)) {
                break;
            }
        }
        return null;
    }

    /**
     * Cost to make {@code cell} body-passable: 0 if already clear, the break
     * cost (and adds it to {@code toBreak}) if a breakable obstruction,
     * {@link ActionCosts#COST_INF} if unbreakable.
     */
    private static double clearCost(NavContext ctx, BlockPos cell, List<BlockPos> toBreak) {
        if (BlockHelper.canWalkThrough(ctx.level, cell)) return 0.0;
        double breakCost = ctx.costOfBreaking(cell);
        if (breakCost >= ActionCosts.COST_INF) return ActionCosts.COST_INF;
        toBreak.add(cell.immutable());
        return breakCost;
    }
}
