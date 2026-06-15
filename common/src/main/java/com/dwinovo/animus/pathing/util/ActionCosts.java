package com.dwinovo.animus.pathing.util;

/**
 * Physics-derived movement cost constants, in game ticks — a 1:1 port of
 * Baritone's {@code ActionCosts} (cabaletta/baritone). See
 * {@code docs/BARITONE_PATHFINDING.md} for the full alignment reference.
 *
 * <p>Every base cost is {@code 20 / speedInBlocksPerSecond}. Fall and jump costs
 * are NOT magic numbers: they come from Minecraft's gravity/drag model
 * {@code velocity(t) = (0.98^t - 1) * -3.92} integrated by {@link #distanceToTicks}.
 * Keeping ticks as the unit lets walk-time + mine-time + placement penalties sum
 * on one scale.
 *
 * <p><b>Tunable settings live in {@link PathSettings}.</b> This class holds only
 * the immutable physics constants.
 */
public final class ActionCosts {

    private ActionCosts() {}

    /** Impossible-move sentinel (finite, so summing several never overflows). */
    public static final double COST_INF = 1_000_000.0;

    // ---- base movement: 20 / (blocks per second) ----

    /** Walk one block: 20 / 4.317. ≈ 4.633 */
    public static final double WALK_ONE_BLOCK = 20.0 / 4.317;
    /** Walk one block in water: 20 / 2.2. ≈ 9.091 (Baritone WALK_ONE_IN_WATER_COST).
     *  Baritone's {@code waterWalkSpeed} interpolates this toward WALK by Depth
     *  Strider level; we don't model the enchant, so it's the plain no-strider value. */
    public static final double WALK_ONE_IN_WATER = 20.0 / 2.2;
    /** Walk one block over soul sand: 2× walk. ≈ 9.266 */
    public static final double WALK_ONE_OVER_SOUL_SAND = WALK_ONE_BLOCK * 2;
    /** Sprint one block: 20 / 5.612. ≈ 3.564 */
    public static final double SPRINT_ONE_BLOCK = 20.0 / 5.612;
    /** Sprint:walk cost ratio. ≈ 0.769 — multiply a clear walk cost by this when sprinting. */
    public static final double SPRINT_MULTIPLIER = SPRINT_ONE_BLOCK / WALK_ONE_BLOCK;
    /** Walking off a block edge: 0.8× walk. ≈ 3.706 */
    public static final double WALK_OFF_BLOCK = WALK_ONE_BLOCK * 0.8;
    /** Re-centering after a fall: walk − walk_off. ≈ 0.927 */
    public static final double CENTER_AFTER_FALL = WALK_ONE_BLOCK - WALK_OFF_BLOCK;
    /** Sneak one block: 20 / 1.3. ≈ 15.385 */
    public static final double SNEAK_ONE_BLOCK = 20.0 / 1.3;
    /** Climb a ladder/vine up one: 20 / 2.35. ≈ 8.511 */
    public static final double LADDER_UP_ONE = 20.0 / 2.35;
    /** Descend a ladder/vine one: 20 / 3.0. ≈ 6.667 */
    public static final double LADDER_DOWN_ONE = 20.0 / 3.0;

    /** Center-to-center distance multiplier for a diagonal step. */
    public static final double SQRT_2 = Math.sqrt(2.0);

    // ---- fall / jump (derived from the gravity model) ----

    /**
     * Per-block fall-time table, {@code FALL_N_BLOCKS_COST[i] = distanceToTicks(i)}
     * for i in 0..256 — the exact Baritone table (it sizes its own at 4097; the
     * heights we ever allow are tiny, so 257 entries is ample).
     */
    public static final double[] FALL_N_BLOCKS_COST = generateFallNBlocksCost();

    /** Ticks to fall 1.25 blocks (a jump's apex height). */
    public static final double FALL_1_25_BLOCKS = distanceToTicks(1.25);
    /** Ticks to fall 0.25 blocks. */
    public static final double FALL_0_25_BLOCKS = distanceToTicks(0.25);
    /** Net cost of jumping one block up. ≈ 3.16 (fall 1.25 − fall 0.25). */
    public static final double JUMP_ONE_BLOCK = FALL_1_25_BLOCKS - FALL_0_25_BLOCKS;

    /**
     * Per-block descent cost used by the A* y-heuristic — Baritone's
     * {@code GoalYLevel} descends at {@code FALL_N_BLOCKS_COST[2] / 2} per block.
     * ≈ 3.89
     */
    public static final double DESCEND_ONE_BLOCK = FALL_N_BLOCKS_COST[2] / 2.0;

    /** Ticks to fall {@code blocks} blocks (table lookup, clamped). */
    public static double fallCost(int blocks) {
        if (blocks <= 0) return 0.0;
        if (blocks < FALL_N_BLOCKS_COST.length) return FALL_N_BLOCKS_COST[blocks];
        return FALL_N_BLOCKS_COST[FALL_N_BLOCKS_COST.length - 1];
    }

    /**
     * Cost of a parkour jump across {@code blocks} of gap (Baritone
     * {@code MovementParkour.costFromJumpDistance}): 2→walk×2, 3→walk×3,
     * 4→sprint×4, plus the flat jump penalty.
     */
    public static double costFromJumpDistance(int blocks) {
        double base = switch (blocks) {
            case 2 -> WALK_ONE_BLOCK * 2;
            case 3 -> WALK_ONE_BLOCK * 3;
            default -> SPRINT_ONE_BLOCK * blocks;   // 4 (requires sprint)
        };
        return base + PathSettings.JUMP_PENALTY;
    }

    // ---- gravity model (Baritone-exact) ----

    /** Downward fall velocity after {@code ticks} ticks: (0.98^t − 1) × −3.92. */
    private static double velocity(int ticks) {
        return (Math.pow(0.98, ticks) - 1) * -3.92;
    }

    /** Ticks to fall {@code distance} blocks, integrating per-tick velocity. */
    private static double distanceToTicks(double distance) {
        if (distance == 0) return 0.0;   // avoid 0/0 NaN
        double remaining = distance;
        int tick = 0;
        while (true) {
            double fall = velocity(tick);
            if (remaining <= fall) {
                return tick + remaining / fall;
            }
            remaining -= fall;
            tick++;
        }
    }

    private static double[] generateFallNBlocksCost() {
        double[] costs = new double[257];
        for (int i = 0; i < 257; i++) {
            costs[i] = distanceToTicks(i);
        }
        return costs;
    }
}
