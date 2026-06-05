package com.dwinovo.animus.pathing.calc;

import com.dwinovo.animus.pathing.movement.Movement;
import com.dwinovo.animus.pathing.util.ActionCosts;
import net.minecraft.core.BlockPos;

/**
 * An A* search node, keyed by a block position. Mirrors Baritone's
 * {@code PathNode}: it caches its own heap position so the open set can do an
 * O(log n) decrease-key when a cheaper route to it is found, instead of the
 * O(n) remove-and-reinsert a {@link java.util.PriorityQueue} would force.
 */
final class PathNode {

    /** Feet position this node represents. */
    final BlockPos pos;
    /** Raw (admissible) heuristic to the goal — used for "closest-so-far" tracking. */
    final double estimatedCostToGoal;

    /** Cost from start (g). {@link ActionCosts#COST_INF} until relaxed. */
    double cost = ActionCosts.COST_INF;
    /** Heap key: g + weighted h. Kept in sync with {@link #cost} on every relax. */
    double combinedCost;

    /** Parent on the cheapest known route, for path reconstruction. */
    PathNode previous;
    /** Movement that led {@link #previous} -> this. */
    Movement via;

    /** Index in the open-set heap array, or {@code -1} when not in the open set. */
    int heapPosition = -1;

    PathNode(BlockPos pos, double estimatedCostToGoal) {
        this.pos = pos;
        this.estimatedCostToGoal = estimatedCostToGoal;
    }

    boolean isOpen() {
        return heapPosition != -1;
    }
}
