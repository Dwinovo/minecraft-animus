package com.dwinovo.animus.pathing.calc;

import com.dwinovo.animus.pathing.util.ActionCosts;
import com.dwinovo.animus.pathing.util.PathSettings;
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
        // Baritone GoalXZ: (diagonal·√2 + straight) × costHeuristic. costHeuristic
        // (≈ sprint cost) IS the per-block weight here — the heap key adds no
        // further multiplier (Baritone folds the weight into the heuristic itself).
        double horizontal = (Math.min(dx, dz) * ActionCosts.SQRT_2 + Math.abs(dx - dz))
                * PathSettings.COST_HEURISTIC;
        // Baritone GoalYLevel: up costs JUMP per block, down costs DESCEND (fall[2]/2).
        int dy = goal.getY() - from.getY();
        double vertical = dy > 0
                ? dy * ActionCosts.JUMP_ONE_BLOCK
                : -dy * ActionCosts.DESCEND_ONE_BLOCK;
        return horizontal + vertical;
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
                        - radius * PathSettings.COST_HEURISTIC);
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
                        - PathSettings.COST_HEURISTIC - ActionCosts.JUMP_ONE_BLOCK);
            }
            @Override public BlockPos center() {
                return goal;
            }
        };
    }

    /**
     * Any of several goals — Baritone {@code GoalComposite}. Satisfied by reaching
     * ANY member; the heuristic is the minimum over members, so a single A* search
     * naturally heads for the CLOSEST reachable one. This is how mining targets a
     * whole field of ore at once instead of greedily picking the nearest (which is
     * often the one walled in and unreachable).
     */
    static NavGoal composite(java.util.List<NavGoal> goals) {
        java.util.List<NavGoal> gs = java.util.List.copyOf(goals);
        if (gs.isEmpty()) {
            throw new IllegalArgumentException("composite goal needs at least one member");
        }
        // Centre = centroid of the members, NOT gs.get(0). The member list is
        // rebuilt every tick (ores re-sorted by distance as the body moves), so a
        // first-member centre would jitter and trip PlayerNav's goal-moved replan
        // every tick. The centroid only shifts when the SET changes (an ore mined
        // or found), which is what "the goal moved" should actually mean.
        long sx = 0, sy = 0, sz = 0;
        for (NavGoal g : gs) {
            BlockPos c = g.center();
            sx += c.getX();
            sy += c.getY();
            sz += c.getZ();
        }
        BlockPos centroid = new BlockPos(
                (int) (sx / gs.size()), (int) (sy / gs.size()), (int) (sz / gs.size()));
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                for (NavGoal g : gs) {
                    if (g.isAt(feet)) return true;
                }
                return false;
            }
            @Override public double heuristic(BlockPos from) {
                double min = Double.MAX_VALUE;
                for (NavGoal g : gs) {
                    min = Math.min(min, g.heuristic(from));
                }
                return min;
            }
            @Override public BlockPos center() {
                return centroid;
            }
        };
    }

    /**
     * Stand in the ore's own column to mine it — Baritone {@code GoalThreeBlocks}:
     * the feet may be at the ore's y, y-1, or y-2 (same x/z), so the body can break
     * the ore straight up. The vertical-shaft mining stance; combined with the
     * "shaft" check (mine the ore directly above your feet) it gives Baritone's
     * mining loop.
     */
    static NavGoal mine(BlockPos ore) {
        BlockPos o = ore.immutable();
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                return feet.getX() == o.getX() && feet.getZ() == o.getZ()
                        && feet.getY() <= o.getY() && feet.getY() >= o.getY() - 2;
            }
            @Override public double heuristic(BlockPos from) {
                double dx = Math.abs(o.getX() - from.getX());
                double dz = Math.abs(o.getZ() - from.getZ());
                double horizontal = (Math.min(dx, dz) * ActionCosts.SQRT_2 + Math.abs(dx - dz))
                        * PathSettings.COST_HEURISTIC;
                // y in {ore.y, ore.y-1, ore.y-2} all count as arrived (GoalThreeBlocks).
                int yDiff = from.getY() - o.getY();
                int adj = yDiff < -1 ? yDiff + 2 : (yDiff == -1 ? 0 : yDiff);
                double vertical = adj > 0
                        ? adj * ActionCosts.JUMP_ONE_BLOCK
                        : -adj * ActionCosts.DESCEND_ONE_BLOCK;
                return horizontal + vertical;
            }
            @Override public BlockPos center() {
                return o;
            }
        };
    }

    /**
     * Get as FAR as possible from {@code from} while holding a y-level — Baritone
     * {@code GoalRunAway} with a maintainY, used for branch mining: when no ore is
     * known, head out along the level to dig fresh tunnel and expose more. Never
     * "arrived" (isAt always false) so the search returns a best-effort partial that
     * walks outward; the next replan continues exploring.
     */
    static NavGoal runAway(BlockPos from, int maintainY) {
        BlockPos f0 = from.immutable();
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                return false;   // never done — keep exploring outward
            }
            @Override public double heuristic(BlockPos fromPos) {
                double dx = fromPos.getX() - f0.getX();
                double dz = fromPos.getZ() - f0.getZ();
                // Farther = lower heuristic (A* minimizes), so it heads outward.
                double horizontal = -Math.sqrt(dx * dx + dz * dz) * PathSettings.COST_HEURISTIC;
                double yPenalty = Math.abs(fromPos.getY() - maintainY)
                        * ActionCosts.JUMP_ONE_BLOCK * 1.5;
                return horizontal + yPenalty;
            }
            @Override public BlockPos center() {
                return f0;
            }
        };
    }
}
