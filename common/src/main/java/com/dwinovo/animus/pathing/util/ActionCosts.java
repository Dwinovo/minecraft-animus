package com.dwinovo.animus.pathing.util;

/**
 * Movement cost constants, measured in game ticks. Mirrors Baritone's
 * {@code ActionCosts} (cabaletta/baritone) — costs are real Minecraft physics
 * derived, not magic numbers, so the A* search optimises for genuinely-fast
 * routes rather than fewest-blocks.
 *
 * <h2>Why ticks</h2>
 * One block of walking takes {@code 20 / blocksPerSecond} ticks. Using ticks
 * as the unit lets us add walk-time + mine-time + a placement penalty on the
 * same scale and have the A* heuristic (Euclidean × WALK) stay admissible.
 *
 * <h2>COST_INF</h2>
 * A deliberately-finite sentinel (1e6, not {@link Double#MAX_VALUE}) so that
 * summing several "impossible" sub-actions never overflows to a negative
 * number that would fool the search. Any movement whose total cost reaches
 * this is discarded by {@link com.dwinovo.animus.pathing.calc.AStar}.
 */
public final class ActionCosts {

    private ActionCosts() {}

    /** Impossible-move sentinel. Movements at or above this are pruned. */
    public static final double COST_INF = 1_000_000.0;

    /** Walking one block on flat ground: 20 / 4.317 b/s. */
    public static final double WALK_ONE_BLOCK = 20.0 / 4.317;

    /** Sprinting one block: 20 / 5.612 b/s (used for parkour jump costing). */
    public static final double SPRINT_ONE_BLOCK = 20.0 / 5.612;

    /**
     * Heuristic inflation factor (weighted A*). The octile distance heuristic is
     * an admissible lower bound, but with terrain modification real per-block
     * costs run well above {@link #WALK_ONE_BLOCK}, so the bound is weak and a
     * pure-admissible search over-explores (the multi-second planning hitches in
     * dug-out terrain). Multiplying h by this (>1) makes the search greedier —
     * far fewer node expansions for near-optimal paths. Tunable: higher = faster
     * but more willing to take a slightly longer route.
     */
    public static final double COST_HEURISTIC = 1.5;

    /**
     * Cost added for placing a scaffolding block (bridging / step-up). Placing
     * is genuinely cheap in real ticks, so this is kept low — the bot bridges
     * gaps readily rather than taking long detours. Raise it if you want it to
     * conserve cobblestone/dirt and prefer walking around.
     */
    public static final double PLACE_BLOCK = 3.0;

    /**
     * Flat tiebreaker added on top of the tool-aware mining duration so that,
     * all else equal, the bot avoids breaking blocks (and zero-hardness
     * blocks still cost something).
     */
    public static final double BREAK_ADDITIONAL = 2.0;

    /** Upward jump (one block) cost, approximated from fall-time symmetry. */
    public static final double JUMP_ONE_BLOCK = 5.0;

    /**
     * Lower-bound cost of descending one block. The cheapest legal descent is
     * a 3-block fall ({@code fallCost(3)/3} ≈ 1.89 ticks/block; falls are
     * capped at {@code maxFallHeight = 3}), and DIG_DOWN / DESCEND edges cost
     * more — so 1.5 stays admissible. It must be {@code > 0}: a zero down-cost
     * made the heuristic blind to vertical progress, so every node straight
     * above a deep target looked "already there" and the partial-path fallback
     * collapsed to the start node (empty path, spurious "no path" failures on
     * dig-down journeys).
     */
    public static final double DESCEND_ONE_BLOCK = 1.5;

    /**
     * Cost of a parkour jump across {@code blocks} of gap (2..4), in ticks. A
     * 2–3 block gap is a walk-jump; a 4 block gap needs sprint physics, so it's
     * costed against the (faster, thus cheaper-per-block) sprint speed. The flat
     * {@link #JUMP_ONE_BLOCK} is added on top so the planner mildly prefers a
     * solid bridge/step when one is equally short.
     */
    public static double costFromJumpDistance(int blocks) {
        double base = switch (blocks) {
            case 2 -> WALK_ONE_BLOCK * 2;
            case 3 -> WALK_ONE_BLOCK * 3;
            default -> SPRINT_ONE_BLOCK * blocks;   // 4+ requires sprint
        };
        return base + JUMP_ONE_BLOCK;
    }

    /** Center-to-center distance multiplier for a diagonal step. */
    public static final double SQRT_2 = 1.41421356;

    /**
     * Per-block fall cost lookup. {@code fallCost(n)} ≈ ticks to fall {@code n}
     * blocks under vanilla gravity (0.08 accel, 0.98 drag). We use a compact
     * closed-form approximation rather than Baritone's 4097-entry simulated
     * table — accurate enough for path ranking at the heights we allow.
     *
     * @param blocks number of blocks fallen (>= 0)
     * @return cost in ticks
     */
    public static double fallCost(int blocks) {
        if (blocks <= 0) return 0.0;
        // t ≈ sqrt(2h/g) with g≈0.08/tick²; tuned to match vanilla feel.
        return Math.sqrt(2.0 * blocks / 0.08) * 0.55 + blocks * 0.3;
    }
}
