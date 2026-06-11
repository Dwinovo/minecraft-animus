package com.dwinovo.animus.pathing.calc;

import com.dwinovo.animus.pathing.movement.Movement;
import com.dwinovo.animus.pathing.movement.Moves;
import com.dwinovo.animus.pathing.util.ActionCosts;
import com.dwinovo.animus.pathing.util.BlockHelper;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A resumable, <em>time-sliced</em> A* search over the {@link Moves} primitive
 * graph — the mineflayer-pathfinder model adapted to a single-threaded server,
 * with Baritone's open-set + heuristic machinery.
 *
 * <p>{@link #step(int)} expands at most {@code budget} nodes per call so a far
 * or complex target never hitches the server tick; the owning goal steps it
 * once per tick until {@link State#DONE}, then reads {@link #result()}. The
 * search stays on the tick thread and reads the live world directly.
 *
 * <h2>Open set &amp; heuristic</h2>
 * Nodes live in a {@link BinaryHeapOpenSet} (O(log n) decrease-key — no
 * duplicate-node churn) and are deduped by a {@link Long2ObjectOpenHashMap}
 * keyed by packed block position (no {@code BlockPos} boxing on the hot path).
 * Edge costs come from the movement primitives (ticks). The heuristic is octile
 * horizontal distance (× walk cost) plus an upward term, inflated by
 * {@link ActionCosts#COST_HEURISTIC} (weighted A*) so the search is greedier and
 * expands far fewer nodes for near-optimal paths.
 *
 * <h2>Bounded</h2>
 * Capped at {@code maxNodes} expansions across all ticks. If the goal isn't
 * reached, the closest node seen (by raw heuristic) is returned as a
 * {@link Path#partial} route; the executor walks it and replans from there.
 */
public final class AStarSearch {

    /** Terminal vs. still-running. {@link #result()} is valid only at {@code DONE}. */
    public enum State { COMPUTING, DONE }

    private final NavContext ctx;
    private final BlockPos start;
    private final BlockPos goal;
    private final int maxNodes;

    /**
     * Near-goal acceptance radius (block²), nonzero only when the goal cell
     * itself can never be entered as a feet position — solid and break-vetoed
     * (a furnace/chest the bot won't grief, bedrock) or a fluid cell. The LLM
     * routinely targets a remembered block's own coordinates; demanding exact
     * node equality there made the search structurally unsatisfiable. 2 blocks
     * matches move_to's arrival semantics ({@code REACHED_DISTANCE_SQR}).
     * Zero for ordinary goals — exact semantics preserved.
     */
    private final double goalToleranceSqr;

    private final Long2ObjectOpenHashMap<PathNode> nodes = new Long2ObjectOpenHashMap<>();
    private final BinaryHeapOpenSet open = new BinaryHeapOpenSet();

    private PathNode best;             // closest-to-goal seen, for partial fallback
    private double bestDist;           // its raw heuristic
    private int expansions = 0;

    private State state = State.COMPUTING;
    private Path result;

    AStarSearch(NavContext ctx, BlockPos start, BlockPos goal, int maxNodes) {
        this.ctx = ctx;
        this.start = start.immutable();
        this.goal = goal.immutable();
        this.maxNodes = maxNodes;

        boolean goalEnterable = BlockHelper.canWalkThrough(ctx.view, this.goal)
                || ctx.costOfBreaking(this.goal) < ActionCosts.COST_INF;
        this.goalToleranceSqr = goalEnterable ? 0.0 : 4.0;

        PathNode startNode = new PathNode(this.start, heuristic(this.start));
        startNode.cost = 0;
        startNode.combinedCost = startNode.estimatedCostToGoal * ActionCosts.COST_HEURISTIC;
        nodes.put(this.start.asLong(), startNode);
        open.insert(startNode);
        best = startNode;
        bestDist = startNode.estimatedCostToGoal;
    }

    /** Current search state. */
    public State state() {
        return state;
    }

    /**
     * The computed route. {@code null} until {@link State#DONE}; thereafter a
     * complete path (goal reached), a {@link Path#partial} best-effort path, or
     * an empty path (no progress).
     */
    public Path result() {
        return result;
    }

    /**
     * Expand at most {@code budget} nodes this tick. Returns {@link State#COMPUTING}
     * if more work remains (call again next tick), or {@link State#DONE} once a
     * path — complete or partial — is available from {@link #result()}.
     */
    public State step(int budget) {
        if (state == State.DONE) {
            return state;
        }

        int budgetLeft = budget;
        while (!open.isEmpty() && expansions < maxNodes && budgetLeft-- > 0) {
            PathNode current = open.removeLowest();
            expansions++;

            if (isAtGoal(current.pos)) {
                result = reconstruct(current, false);
                state = State.DONE;
                return state;
            }

            if (current.estimatedCostToGoal < bestDist) {
                bestDist = current.estimatedCostToGoal;
                best = current;
            }

            for (Movement mv : Moves.generate(ctx, current.pos)) {
                if (mv.cost >= ActionCosts.COST_INF) continue;
                double tentativeG = current.cost + mv.cost;
                long key = mv.dest.asLong();

                PathNode neighbor = nodes.get(key);
                if (neighbor == null) {
                    neighbor = new PathNode(mv.dest.immutable(), heuristic(mv.dest));
                    nodes.put(key, neighbor);
                }
                if (tentativeG >= neighbor.cost) continue;   // not an improvement

                neighbor.cost = tentativeG;
                neighbor.combinedCost =
                        tentativeG + neighbor.estimatedCostToGoal * ActionCosts.COST_HEURISTIC;
                neighbor.previous = current;
                neighbor.via = mv;
                if (neighbor.isOpen()) {
                    open.update(neighbor);
                } else {
                    open.insert(neighbor);
                }
            }
        }

        // Terminated (open exhausted or node cap hit) vs. just out of this tick's
        // budget. Only the former produces a result; otherwise resume next tick.
        if (open.isEmpty() || expansions >= maxNodes) {
            result = (best.via == null)
                    ? new Path(start, start, Collections.emptyList(), true)
                    : reconstruct(best, true);
            state = State.DONE;
        }
        return state;
    }

    private boolean isAtGoal(BlockPos pos) {
        if (pos.equals(goal)) return true;
        return goalToleranceSqr > 0 && pos.distSqr(goal) <= goalToleranceSqr;
    }

    private Path reconstruct(PathNode end, boolean partial) {
        ArrayDeque<Movement> stack = new ArrayDeque<>();
        PathNode cur = end;
        while (cur != null && cur.via != null) {
            stack.push(cur.via);
            cur = cur.previous;
        }
        List<Movement> moves = new ArrayList<>(stack);
        return new Path(start, end.pos, moves, partial);
    }

    /**
     * Octile horizontal distance (× walk cost) plus an upward term — an
     * admissible lower bound on remaining cost. The search inflates this by
     * {@link ActionCosts#COST_HEURISTIC} at the heap-key level; this raw value is
     * kept per-node for "closest-to-goal" partial-path tracking.
     */
    private double heuristic(BlockPos from) {
        double dx = Math.abs(goal.getX() - from.getX());
        double dz = Math.abs(goal.getZ() - from.getZ());
        double octile = (Math.min(dx, dz) * ActionCosts.SQRT_2 + Math.abs(dx - dz))
                * ActionCosts.WALK_ONE_BLOCK;
        // Downward must cost > 0 (DESCEND_ONE_BLOCK is a lower bound on any
        // legal descent): with a free down-direction, every node straight above
        // a deep target scored h == 0, so the best-node tracker never saw
        // progress and partial paths collapsed to the start node.
        int dy = goal.getY() - from.getY();
        double vertical = dy > 0
                ? dy * ActionCosts.JUMP_ONE_BLOCK
                : -dy * ActionCosts.DESCEND_ONE_BLOCK;
        return octile + vertical;
    }
}
