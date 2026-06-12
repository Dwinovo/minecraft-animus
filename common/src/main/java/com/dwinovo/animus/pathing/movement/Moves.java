package com.dwinovo.animus.pathing.movement;

import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.util.ActionCosts;
import com.dwinovo.animus.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the candidate {@link Movement}s reachable from a feet-position,
 * each self-costed against the {@link NavContext} snapshot. Infeasible
 * movements (no scaffolding to bridge, unbreakable obstruction, hazard,
 * fall too deep) are simply not emitted.
 *
 * <h2>Primitive set (mineflayer-parity)</h2>
 * <ul>
 *   <li><b>Traverse</b> — same-Y step; bridges gaps by placing a floor block
 *       and mines head/feet obstructions.</li>
 *   <li><b>Ascend</b> — step up one block; mines head-room and may place the
 *       step block to climb a ledge that isn't there.</li>
 *   <li><b>Descend / Fall</b> — step out and drop to the first safe floor
 *       within {@link NavContext#maxFallHeight}.</li>
 *   <li><b>Diagonal</b> — same-Y step to a corner cell; emitted only when both
 *       orthogonal corner cells are open (no corner-clipping), mirrors
 *       Baritone {@code MovementDiagonal} / mineflayer diagonal moves.</li>
 *   <li><b>Pillar</b> — jump straight up one block, placing a scaffolding block
 *       beneath as you rise (mineflayer "move up" / jump-place). Needs a
 *       scaffold block and head-room.</li>
 *   <li><b>DigDown</b> — mine the floor block underfoot and drop one (mineflayer
 *       "dig down"). Only when a solid floor exists one block lower to land on.</li>
 *   <li><b>Parkour</b> — a committed running jump across a 2-4 block gap at the
 *       same level (Baritone {@code MovementParkour}); the executor supplies
 *       the impulse, the planner only asserts the corridor is jumpable.</li>
 * </ul>
 */
public final class Moves {

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    /** The four diagonal corners as orthogonal direction pairs. */
    private static final Direction[][] DIAGONALS = {
            {Direction.NORTH, Direction.EAST},
            {Direction.NORTH, Direction.WEST},
            {Direction.SOUTH, Direction.EAST},
            {Direction.SOUTH, Direction.WEST},
    };

    /** Maximum parkour gap (blocks) — 4 needs sprint physics, the vanilla cap. */
    private static final int MAX_PARKOUR = 4;

    private Moves() {}

    /** All feasible movements out of {@code from}. */
    public static List<Movement> generate(NavContext ctx, BlockPos from) {
        List<Movement> out = new ArrayList<>(18);
        for (Direction dir : HORIZONTAL) {
            Movement t = traverse(ctx, from, dir);
            if (t != null) out.add(t);
            Movement a = ascend(ctx, from, dir);
            if (a != null) out.add(a);
            Movement d = descend(ctx, from, dir);
            if (d != null) out.add(d);
            Movement p = parkour(ctx, from, dir);
            if (p != null) out.add(p);
        }
        for (Direction[] pair : DIAGONALS) {
            Movement g = diagonal(ctx, from, pair[0], pair[1]);
            if (g != null) out.add(g);
        }
        Movement up = pillar(ctx, from);
        if (up != null) out.add(up);
        Movement down = digDown(ctx, from);
        if (down != null) out.add(down);
        swims(ctx, from, out);
        return out;
    }

    // ---- Swim: water as first-class (expensive) terrain ----

    /**
     * Emitted only while the FROM cell is water — a dry body never plans into
     * water (entering deliberately stays unsupported; bridging wins on cost
     * anyway). In water: lateral water→water strokes plus a vertical stroke
     * toward the surface, all at {@link ActionCosts#SWIM_ONE_BLOCK} (~2× walk,
     * so land routes dominate the moment the search reaches shore). The shore
     * EXIT needs no special edge: TRAVERSE/ASCEND generate from a water cell
     * like any other, so "swim to the bank, step/jump out, walk on" is one
     * continuous plan — the entire reason a dunked task no longer needs an
     * emergency reflex and a pile of yield timers.
     */
    private static void swims(NavContext ctx, BlockPos from, List<Movement> out) {
        BlockGetter level = ctx.view;
        if (!BlockHelper.isWater(level, from)) return;
        for (Direction dir : HORIZONTAL) {
            BlockPos dest = from.relative(dir);
            if (!BlockHelper.isWater(level, dest)) continue;
            if (!BlockHelper.canOccupyInWater(level, dest.above())) continue;
            if (BlockHelper.isHazard(level, dest) || BlockHelper.isHazard(level, dest.above())) continue;
            out.add(new Movement(Movement.Kind.SWIM, from, dest,
                    ActionCosts.SWIM_ONE_BLOCK, List.of(), null));
        }
        // Up toward the surface (water→water only; surfacing into the air gap
        // above the top water cell is the lateral/exit moves' business).
        BlockPos up = from.above();
        if (BlockHelper.isWater(level, up)
                && BlockHelper.canOccupyInWater(level, up.above())
                && !BlockHelper.isHazard(level, up)) {
            out.add(new Movement(Movement.Kind.SWIM, from, up,
                    ActionCosts.SWIM_ONE_BLOCK, List.of(), null));
        }
    }

    // ---- Traverse: same-Y step, bridge gaps, dig obstructions ----

    private static Movement traverse(NavContext ctx, BlockPos from, Direction dir) {
        BlockGetter level = ctx.view;
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
        return new Movement(Movement.Kind.TRAVERSE, from, dest, cost, toBreak, toPlace);
    }

    // ---- Ascend: up one block, dig head-room, place step if needed ----

    private static Movement ascend(NavContext ctx, BlockPos from, Direction dir) {
        BlockGetter level = ctx.view;
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
        return new Movement(Movement.Kind.ASCEND, from, dest, cost, toBreak, toPlace);
    }

    // ---- Descend / Fall: step out and drop to first safe floor ----

    private static Movement descend(NavContext ctx, BlockPos from, Direction dir) {
        BlockGetter level = ctx.view;
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
                return new Movement(kind, from, feet, total, toBreak, null);
            }
            // If the cell we'd pass through is itself an obstruction we can't
            // clear, stop scanning this column.
            if (!BlockHelper.canWalkThrough(level, feet)) {
                break;
            }
        }
        return null;
    }

    // ---- Diagonal: same-Y corner step over EXISTING ground only ----

    /**
     * Pure walking shortcut across a corner. Deliberately conservative: it
     * neither breaks nor places anything — diagonal bridging/digging produces
     * the ugly, unnatural "corner-to-corner staircase" (blocks touching only at
     * their corners), so all terrain modification is left to the cardinal moves,
     * which lay straight bridges that turn at 90°. A diagonal is emitted only
     * when the destination is already a clear, standable cell and neither
     * orthogonal cell we cut between is obstructed (no corner-clipping).
     */
    private static Movement diagonal(NavContext ctx, BlockPos from, Direction a, Direction b) {
        BlockGetter level = ctx.view;
        BlockPos dest = from.relative(a).relative(b);

        // Destination must already be a valid standing spot — solid floor below,
        // body clearance at feet+head. No placing, no breaking.
        if (!BlockHelper.isStandable(level, dest)) return null;

        // Both orthogonal cells we pass between must be open at both body
        // heights, otherwise the entity would clip a corner.
        BlockPos cornerA = from.relative(a);
        BlockPos cornerB = from.relative(b);
        if (!BlockHelper.canWalkThrough(level, cornerA)
                || !BlockHelper.canWalkThrough(level, cornerA.above())) return null;
        if (!BlockHelper.canWalkThrough(level, cornerB)
                || !BlockHelper.canWalkThrough(level, cornerB.above())) return null;

        if (BlockHelper.isHazard(level, dest) || BlockHelper.isHazard(level, dest.above())) return null;

        double cost = ActionCosts.WALK_ONE_BLOCK * ActionCosts.SQRT_2;
        return new Movement(Movement.Kind.DIAGONAL, from, dest, cost, List.of(), null);
    }

    // ---- Pillar: jump up one, placing a block beneath as we rise ----

    private static Movement pillar(NavContext ctx, BlockPos from) {
        BlockGetter level = ctx.view;
        BlockPos dest = from.above();      // feet end one block up
        BlockPos newHead = from.above(2);  // head room while standing on the new block

        // Need a scaffold block to drop under our feet (the current feet cell).
        double placeCost = ctx.costOfPlacing(from);
        if (placeCost >= ActionCosts.COST_INF) return null;

        double cost = ActionCosts.JUMP_ONE_BLOCK + placeCost;
        List<BlockPos> toBreak = new ArrayList<>(1);
        double headBreak = clearCost(ctx, newHead, toBreak);
        if (headBreak >= ActionCosts.COST_INF) return null;
        cost += headBreak;

        if (BlockHelper.isHazard(level, dest) || BlockHelper.isHazard(level, newHead)) return null;
        // toPlace deliberately null: placement is the PILLAR phase's own job,
        // done mid-jump at the entity's LIVE base Y (which may differ from the
        // plan by a block) — the generic PREPARE_PLACE phase never runs for
        // pillars, so a toPlace here would be dead, misleading data.
        return new Movement(Movement.Kind.PILLAR, from, dest, cost, toBreak, null);
    }

    // ---- DigDown: mine the floor underfoot and drop one ----

    private static Movement digDown(NavContext ctx, BlockPos from) {
        BlockGetter level = ctx.view;
        BlockPos below = from.below();       // floor block to mine == destination feet
        BlockPos landing = from.below(2);    // must be solid to stand on after the drop

        if (!BlockHelper.canWalkOn(level, landing)) return null;

        double breakCost = ctx.costOfBreaking(below);
        if (breakCost >= ActionCosts.COST_INF) return null; // air/unbreakable handled by descend
        if (BlockHelper.isHazard(level, below) || BlockHelper.isHazard(level, landing)) return null;

        List<BlockPos> toBreak = new ArrayList<>(1);
        toBreak.add(below.immutable());
        double cost = breakCost + ActionCosts.fallCost(1);
        return new Movement(Movement.Kind.DIG_DOWN, from, below, cost, toBreak, null);
    }

    // ---- Parkour: a running jump across a 2-4 block gap at the same level ----

    /**
     * A single atomic edge that clears a gap by jumping, rather than bridging it
     * with scaffolding (Baritone {@code MovementParkour}). Emitted only when
     * there's a genuine gap straight ahead (no floor immediately in front) and a
     * clear air corridor at body height across to the nearest landing within
     * {@link #MAX_PARKOUR}. The momentum + jump timing are supplied by the
     * executor at runtime; the planner only asserts the gap is jumpable.
     *
     * <p>Conservative scope: flat (same-Y) landings only — ascend/drop parkour
     * and parkour-place are deferred. Neither breaks nor places anything.
     */
    private static Movement parkour(NavContext ctx, BlockPos from, Direction dir) {
        BlockGetter level = ctx.view;
        // Only a real gap warrants a jump: a floor immediately ahead means a
        // plain traverse/descend already covers it.
        if (BlockHelper.canWalkOn(level, from.relative(dir).below())) return null;
        // Head-room to jump from the takeoff.
        if (!BlockHelper.canWalkThrough(level, from.above(2))) return null;

        for (int d = 2; d <= MAX_PARKOUR; d++) {
            // Every cell from 1..d must be clear air at feet & head (no clipping).
            boolean clear = true;
            for (int i = 1; i <= d; i++) {
                BlockPos g = from.relative(dir, i);
                if (!BlockHelper.canWalkThrough(level, g)
                        || !BlockHelper.canWalkThrough(level, g.above())) {
                    clear = false;
                    break;
                }
                // The arc rises ~1.25, so over the gap interior the head sweeps
                // the third cell too — a 3-high ceiling bonks the jump short.
                if (i < d && !BlockHelper.canWalkThrough(level, g.above(2))) {
                    clear = false;
                    break;
                }
            }
            if (!clear) break;   // blocked within the gap → no longer jump is possible

            BlockPos land = from.relative(dir, d);
            if (!BlockHelper.canWalkOn(level, land.below())) continue;   // no floor here, try farther
            if (BlockHelper.isHazard(level, land) || BlockHelper.isHazard(level, land.above())) break;
            double cost = ActionCosts.costFromJumpDistance(d);
            return new Movement(Movement.Kind.PARKOUR, from, land, cost, List.of(), null);
        }
        return null;
    }

    /**
     * Cost to make {@code cell} body-passable: 0 if already clear, the break
     * cost (and adds it to {@code toBreak}) if a breakable obstruction,
     * {@link ActionCosts#COST_INF} if unbreakable.
     */
    private static double clearCost(NavContext ctx, BlockPos cell, List<BlockPos> toBreak) {
        if (BlockHelper.canWalkThrough(ctx.view, cell)) return 0.0;
        double breakCost = ctx.costOfBreaking(cell);
        if (breakCost >= ActionCosts.COST_INF) return ActionCosts.COST_INF;
        toBreak.add(cell.immutable());
        return breakCost;
    }
}
