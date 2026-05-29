package com.dwinovo.animus.pathing.calc;

import com.dwinovo.animus.pathing.movement.Movement;
import com.dwinovo.animus.pathing.movement.Moves;
import com.dwinovo.animus.pathing.util.ActionCosts;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* search over the {@link Moves} primitive graph. Produces a {@link Path}
 * of {@link Movement} edges from a start feet-position to (near) a goal.
 *
 * <h2>Cost &amp; heuristic</h2>
 * Edge costs come from the movement primitives (ticks; walk + mine + place).
 * The heuristic is the straight-line horizontal distance × {@code WALK} plus
 * a small upward term — a lower bound on remaining cost, keeping the search
 * close to admissible so paths are near-optimal without over-exploring.
 *
 * <h2>Bounded</h2>
 * Capped at {@link #maxNodes} expansions so a single search can never stall
 * the server tick. If the goal isn't reached within budget, the best node by
 * heuristic is returned as a {@link Path#partial} route; the executor walks
 * it and replans from the new position.
 */
public final class AStar {

    /** Hard cap on node expansions per search. */
    private final int maxNodes;

    public AStar(int maxNodes) {
        this.maxNodes = maxNodes;
    }

    public AStar() {
        this(10_000);
    }

    /** Internal open/closed bookkeeping node, keyed by block position. */
    private static final class Node {
        final BlockPos pos;
        double g;          // cost from start
        double f;          // g + heuristic
        Node parent;
        Movement via;      // movement that led parent -> this
        boolean closed;

        Node(BlockPos pos) { this.pos = pos; }
    }

    public Path compute(NavContext ctx, BlockPos start, BlockPos goal) {
        start = start.immutable();
        goal = goal.immutable();

        Map<BlockPos, Node> nodes = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));

        Node startNode = new Node(start);
        startNode.g = 0;
        startNode.f = heuristic(start, goal);
        nodes.put(start, startNode);
        open.add(startNode);

        Node best = startNode;          // closest-to-goal seen, for partial fallback
        double bestH = startNode.f;
        int expansions = 0;

        while (!open.isEmpty() && expansions < maxNodes) {
            Node current = open.poll();
            if (current.closed) continue;
            current.closed = true;
            expansions++;

            if (current.pos.equals(goal)) {
                return reconstruct(start, goal, current, false);
            }

            double h = heuristic(current.pos, goal);
            if (h < bestH) {
                bestH = h;
                best = current;
            }

            for (Movement mv : Moves.generate(ctx, current.pos)) {
                if (mv.cost >= ActionCosts.COST_INF) continue;
                BlockPos np = mv.dest;
                double tentativeG = current.g + mv.cost;

                Node neighbor = nodes.computeIfAbsent(np, Node::new);
                if (neighbor.closed && tentativeG >= neighbor.g) continue;
                if (neighbor.via != null && tentativeG >= neighbor.g) continue;

                neighbor.parent = current;
                neighbor.via = mv;
                neighbor.g = tentativeG;
                neighbor.f = tentativeG + heuristic(np, goal);
                neighbor.closed = false;
                open.add(neighbor);
            }
        }

        // Goal not reached within budget: return best-effort partial path.
        if (best == startNode) {
            return new Path(start, start, Collections.emptyList(), true);
        }
        return reconstruct(start, best.pos, best, true);
    }

    private static Path reconstruct(BlockPos start, BlockPos end, Node end_, boolean partial) {
        ArrayDeque<Movement> stack = new ArrayDeque<>();
        Node cur = end_;
        while (cur != null && cur.via != null) {
            stack.push(cur.via);
            cur = cur.parent;
        }
        List<Movement> moves = new ArrayList<>(stack);
        return new Path(start, end, moves, partial);
    }

    /**
     * Admissible-ish heuristic: horizontal Euclidean distance × walk cost,
     * plus upward delta × jump cost (downward is free — falling is cheap).
     */
    private static double heuristic(BlockPos from, BlockPos goal) {
        double dx = goal.getX() - from.getX();
        double dz = goal.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int dy = goal.getY() - from.getY();
        double vertical = dy > 0 ? dy * ActionCosts.JUMP_ONE_BLOCK : 0;
        return horizontal * ActionCosts.WALK_ONE_BLOCK + vertical;
    }
}
