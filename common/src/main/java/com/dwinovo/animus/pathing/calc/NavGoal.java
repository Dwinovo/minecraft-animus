package com.dwinovo.animus.pathing.calc;

import com.dwinovo.animus.pathing.util.ActionCosts;
import net.minecraft.core.BlockPos;

/**
 * What the search is trying to reach — Baritone's {@code Goal} shape. Until
 * now arrival semantics were written five times in five places (the search's
 * tolerance hack, move_to's radius, three hand-rolled "stand next to X"
 * pickers); a goal object makes the search terminate, the heuristic aim and
 * the caller assert against the SAME definition.
 *
 * <p>Node-domain only: {@link #isAt} judges feet CELLS during the search.
 * Live-entity arrival predicates (exact doubles, reach distances) remain the
 * task layer's business — they answer a different question ("is my body close
 * enough") than the search's ("may this node end the path").
 */
public interface NavGoal {

    /** May a path legitimately end at this feet cell? */
    boolean isAt(BlockPos feet);

    /** Admissible lower bound (ticks) on the remaining cost from {@code from}. */
    double heuristic(BlockPos from);

    /** Representative position — diagnostics, goal-moved checks, locate math. */
    BlockPos center();

    // ---- the shared octile + vertical point bound ----

    /**
     * Octile horizontal distance (× walk cost) plus a vertical term — the
     * admissible point-to-point bound every concrete goal builds on. Downward
     * must cost &gt; 0 (see {@link ActionCosts#DESCEND_ONE_BLOCK}): with a free
     * down-direction every node straight above a deep target scored h == 0
     * and partial paths collapsed to the start node.
     */
    static double pointBound(BlockPos goal, BlockPos from) {
        double dx = Math.abs(goal.getX() - from.getX());
        double dz = Math.abs(goal.getZ() - from.getZ());
        double octile = (Math.min(dx, dz) * ActionCosts.SQRT_2 + Math.abs(dx - dz))
                * ActionCosts.WALK_ONE_BLOCK;
        int dy = goal.getY() - from.getY();
        double vertical = dy > 0
                ? dy * ActionCosts.JUMP_ONE_BLOCK
                : -dy * ActionCosts.DESCEND_ONE_BLOCK;
        return octile + vertical;
    }

    // ---- factories ----

    /** Exactly this feet cell. */
    static NavGoal exact(BlockPos pos) {
        BlockPos goal = pos.immutable();
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                return feet.equals(goal);
            }
            @Override public double heuristic(BlockPos from) {
                return pointBound(goal, from);
            }
            @Override public BlockPos center() {
                return goal;
            }
        };
    }

    /** Any feet cell within {@code radius} (Euclidean, blocks) of {@code pos}. */
    static NavGoal near(BlockPos pos, double radius) {
        BlockPos goal = pos.immutable();
        double radiusSqr = radius * radius;
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                return feet.distSqr(goal) <= radiusSqr;
            }
            @Override public double heuristic(BlockPos from) {
                // Stopping up to `radius` short: subtract the walkable slack,
                // clamped — keeps the bound admissible for in-radius nodes.
                return Math.max(0.0, pointBound(goal, from)
                        - radius * ActionCosts.WALK_ONE_BLOCK);
            }
            @Override public BlockPos center() {
                return goal;
            }
        };
    }

    /**
     * Any feet cell horizontally adjacent to {@code target} (±1 step on one
     * axis), at the target's height ±1 — "stand next to this block so you can
     * work on it". The standability of the ending cell is the graph's own
     * guarantee (only occupiable cells become nodes).
     */
    static NavGoal adjacent(BlockPos target) {
        BlockPos goal = target.immutable();
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                int dx = Math.abs(feet.getX() - goal.getX());
                int dz = Math.abs(feet.getZ() - goal.getZ());
                int dy = Math.abs(feet.getY() - goal.getY());
                return dx + dz == 1 && dy <= 1;
            }
            @Override public double heuristic(BlockPos from) {
                // One step + one jump of slack vs the point bound.
                return Math.max(0.0, pointBound(goal, from)
                        - ActionCosts.WALK_ONE_BLOCK - ActionCosts.JUMP_ONE_BLOCK);
            }
            @Override public BlockPos center() {
                return goal;
            }
        };
    }
}
