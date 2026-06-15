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

    /**
     * Reach the {@code (x, z)} column at ANY height — a 1:1 port of Baritone
     * {@code GoalXZ} ({@code goto x z}). The heuristic is the pure horizontal
     * octile term (no vertical), so the search heads for the column and stops at
     * whatever ground exists there. This is the "go to a location" goal: the
     * caller's Y is irrelevant, so a wrong/guessed Y can never make it unreachable.
     */
    static NavGoal column(int x, int z) {
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                return feet.getX() == x && feet.getZ() == z;
            }
            @Override public double heuristic(BlockPos from) {
                double dx = Math.abs(x - from.getX());
                double dz = Math.abs(z - from.getZ());
                return (Math.min(dx, dz) * ActionCosts.SQRT_2 + Math.abs(dx - dz))
                        * PathSettings.COST_HEURISTIC;
            }
            @Override public BlockPos center() {
                return new BlockPos(x, 0, z);   // y irrelevant — goal is XZ-only
            }
        };
    }

    /**
     * Reach a Y level at ANY X/Z — a 1:1 port of Baritone {@code GoalYLevel}
     * ({@code goto y}): "change elevation to this height" (climb to the surface,
     * descend to a mining depth). Heuristic is the pure vertical term — up costs
     * {@link ActionCosts#JUMP_ONE_BLOCK} per block, down {@link ActionCosts#DESCEND_ONE_BLOCK}.
     */
    static NavGoal yLevel(int level) {
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                return feet.getY() == level;
            }
            @Override public double heuristic(BlockPos from) {
                int cy = from.getY();
                if (cy > level) return ActionCosts.DESCEND_ONE_BLOCK * (cy - level);
                if (cy < level) return (level - cy) * ActionCosts.JUMP_ONE_BLOCK;
                return 0.0;
            }
            @Override public BlockPos center() {
                return new BlockPos(0, level, 0);   // x/z irrelevant — goal is Y-only
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
                // Baritone GoalNear.heuristic IS the full GoalBlock.calculate — the radius
                // only relaxes isInGoal, it is NOT subtracted from the aim. (Slightly
                // inadmissible, but that's exactly what Baritone does, so node ordering and
                // the best-so-far partial match.)
                return pointBound(goal, from);
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
     * Stand in the ore's own column to mine it — the family of Baritone mining
     * stance goals, parameterised by how far BELOW the ore the feet may be:
     * <ul>
     *   <li>{@code maxBelow == 0} → {@code GoalBlock}: feet exactly at the ore;</li>
     *   <li>{@code maxBelow == 1} → {@code GoalTwoBlocks}: feet at the ore or one below;</li>
     *   <li>{@code maxBelow == 2} → {@code GoalThreeBlocks}: feet at the ore, one, or two below.</li>
     * </ul>
     * Which one a given ore gets is decided by {@code MineCompanionTask.coalesce}
     * (Baritone {@code MineProcess.coalesce}, forceInternalMining=true): the bottom
     * of a vertical run gets {@code GoalBlock} so the body mines it in place rather
     * than tunnelling under it. The vertical term in the heuristic folds the whole
     * accepted band to zero cost (matching {@code GoalBlock.calculate}'s y-fold).
     */
    static NavGoal mineColumn(BlockPos ore, int maxBelow) {
        BlockPos o = ore.immutable();
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                return feet.getX() == o.getX() && feet.getZ() == o.getZ()
                        && feet.getY() <= o.getY() && feet.getY() >= o.getY() - maxBelow;
            }
            @Override public double heuristic(BlockPos from) {
                double dx = Math.abs(o.getX() - from.getX());
                double dz = Math.abs(o.getZ() - from.getZ());
                double horizontal = (Math.min(dx, dz) * ActionCosts.SQRT_2 + Math.abs(dx - dz))
                        * PathSettings.COST_HEURISTIC;
                // Feet anywhere in {o.y .. o.y-maxBelow} count as arrived: fold that
                // band to zero, mirroring Goal{Block,Two,Three}Blocks.heuristic.
                int yDiff = from.getY() - o.getY();
                int adj = yDiff >= 0 ? yDiff : Math.min(0, yDiff + maxBelow);
                // GoalYLevel.calculate(0, adj): above the goal (adj>0) we DESCEND to it,
                // below it (adj<0) we ASCEND. (The old mine() had these two swapped,
                // overestimating descents — an inadmissible heuristic.)
                double vertical = adj > 0
                        ? adj * ActionCosts.DESCEND_ONE_BLOCK
                        : -adj * ActionCosts.JUMP_ONE_BLOCK;
                return horizontal + vertical;
            }
            @Override public BlockPos center() {
                return o;
            }
        };
    }

    /** GoalThreeBlocks shorthand (feet at the ore, one, or two below). */
    static NavGoal mine(BlockPos ore) {
        return mineColumn(ore, 2);
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
                // Baritone GoalRunAway.heuristic: −GoalXZ.calculate(dx,dz) (octile×weight,
                // negated so farther = lower h = preferred), then, with a maintainY,
                // min*0.6 + GoalYLevel.calculate(maintainY, y)*1.5.
                double dx = Math.abs(f0.getX() - fromPos.getX());
                double dz = Math.abs(f0.getZ() - fromPos.getZ());
                double xz = (Math.min(dx, dz) * ActionCosts.SQRT_2 + Math.abs(dx - dz))
                        * PathSettings.COST_HEURISTIC;
                double min = -xz;
                int cy = fromPos.getY();
                double yLevel = cy > maintainY ? (cy - maintainY) * ActionCosts.DESCEND_ONE_BLOCK
                        : cy < maintainY ? (maintainY - cy) * ActionCosts.JUMP_ONE_BLOCK : 0.0;
                return min * 0.6 + yLevel * 1.5;
            }
            @Override public BlockPos center() {
                return f0;
            }
        };
    }
}
