package com.dwinovo.animus.pathing.calc;

import net.minecraft.core.BlockPos;

/**
 * Configuration and factory for {@link AStarSearch}. Holds the per-search node
 * budget and hands out resumable, time-sliced searches.
 *
 * <h2>Time-sliced by default</h2>
 * Call {@link #newSearch} and {@code step()} it once per tick — the search
 * spreads its work across ticks so no single tick stalls, even with several
 * entities planning at once.
 */
public final class AStar {

    /** Default hard cap on node expansions per search (across all ticks). */
    public static final int DEFAULT_MAX_NODES = 10_000;

    /**
     * Default per-tick expansion budget. Picked so a typical near search
     * finishes in a single tick while a worst-case search (the full node cap)
     * spreads over a handful of ticks (~{@value #DEFAULT_MAX_NODES} /
     * {@value #DEFAULT_NODES_PER_TICK} ≈ 7 ticks) instead of hitching one.
     */
    public static final int DEFAULT_NODES_PER_TICK = 1_500;

    /** Hard cap on node expansions per search. */
    private final int maxNodes;

    public AStar(int maxNodes) {
        this.maxNodes = maxNodes;
    }

    public AStar() {
        this(DEFAULT_MAX_NODES);
    }

    /** Begin a resumable, time-sliced search toward an explicit {@link NavGoal}. */
    public AStarSearch newSearch(NavContext ctx, BlockPos start, NavGoal goal) {
        return new AStarSearch(ctx, start, goal, maxNodes);
    }

    /**
     * Convenience for "get to this cell": exact when the cell is enterable;
     * when it can never be a feet position — solid and break-vetoed (a
     * furnace/chest the bot won't grief, bedrock) or a fluid cell — relaxes to
     * {@code near(goal, 2)}, matching move_to's arrival semantics. The LLM
     * routinely targets a remembered block's own coordinates; demanding exact
     * node equality there made the search structurally unsatisfiable.
     */
    public AStarSearch newSearch(NavContext ctx, BlockPos start, BlockPos goal) {
        boolean enterable = com.dwinovo.animus.pathing.util.BlockHelper.canWalkThrough(ctx.view, goal)
                || ctx.costOfBreaking(goal) < com.dwinovo.animus.pathing.util.ActionCosts.COST_INF;
        return newSearch(ctx, start, enterable ? NavGoal.exact(goal) : NavGoal.near(goal, 2.0));
    }
}
