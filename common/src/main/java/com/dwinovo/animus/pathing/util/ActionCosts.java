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
    /** Sprinting one block: 20 / 5.612 b/s. Unused until we enable sprint. */
    public static final double SPRINT_ONE_BLOCK = 20.0 / 5.612;
    /** Walking one block through water: 20 / 2.2 b/s. */
    public static final double WALK_ONE_IN_WATER = 20.0 / 2.2;

    /**
     * Cost added for placing a scaffolding block (bridging / step-up). Large
     * on purpose — placing is cheap in real ticks, but we want the bot to
     * prefer walking around over consuming its limited cobblestone/dirt.
     * Lower this to make it bridge more eagerly.
     */
    public static final double PLACE_BLOCK = 20.0;

    /**
     * Flat tiebreaker added on top of the tool-aware mining duration so that,
     * all else equal, the bot avoids breaking blocks (and zero-hardness
     * blocks still cost something).
     */
    public static final double BREAK_ADDITIONAL = 2.0;

    /** Upward jump (one block) cost, approximated from fall-time symmetry. */
    public static final double JUMP_ONE_BLOCK = 5.0;

    /** Center-to-center distance for a diagonal step (unused in the 4-move set). */
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
