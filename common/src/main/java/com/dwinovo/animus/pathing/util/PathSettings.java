package com.dwinovo.animus.pathing.util;

/**
 * Tunable pathfinding parameters — a 1:1 port of the relevant defaults from
 * Baritone's {@code Settings} (cabaletta/baritone). Centralised here so every
 * cost / search / executor knob matches Baritone and can be debugged against
 * its known-good behaviour. See {@code docs/BARITONE_PATHFINDING.md}.
 *
 * <p>Physics constants (walk/jump/fall costs) live in {@link ActionCosts}; this
 * class holds only the values Baritone exposes as user settings.
 */
public final class PathSettings {

    private PathSettings() {}

    // ---- A* search ----

    /**
     * Heuristic inflation (weighted, inadmissible A*). Baritone default 3.563 —
     * just under {@link ActionCosts#SPRINT_ONE_BLOCK} so the search races to the
     * goal and accepts mild suboptimality for far fewer node expansions.
     */
    public static final double COST_HEURISTIC = 3.563;

    /** Best-so-far backoff coefficients — one candidate endpoint per value, to escape local minima. */
    public static final double[] COEFFICIENTS = {1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0};

    /** Minimum start→node distance (blocks) for a partial path to be usable. */
    public static final double MIN_DIST_PATH = 5.0;

    /** Skip re-propagating cost improvements smaller than this (FP-noise guard). */
    public static final double MIN_IMPROVEMENT = 0.01;

    /** Stop the initial search this long after the first usable path is found. */
    public static final long PRIMARY_TIMEOUT_MS = 500L;
    /** Hard cap on the initial search when no path is found yet. */
    public static final long FAILURE_TIMEOUT_MS = 2000L;
    /** Plan-ahead (next segment) variant of the primary timeout. */
    public static final long PLAN_AHEAD_PRIMARY_TIMEOUT_MS = 4000L;
    /** Plan-ahead variant of the failure timeout. */
    public static final long PLAN_AHEAD_FAILURE_TIMEOUT_MS = 5000L;

    // ---- cost penalties ----

    /** Flat tiebreaker added per block break (prefer non-mining routes). */
    public static final double BLOCK_BREAK_ADDITIONAL_PENALTY = 2.0;
    /** Cost added per placed block (conserve blocks; high to discourage placing). */
    public static final double BLOCK_PLACEMENT_PENALTY = 20.0;
    /** Extra cost per spacebar use (jump/ascend/pillar/parkour) — models hunger drain. */
    public static final double JUMP_PENALTY = 2.0;
    /** Extra cost per block of walking on water (fast hunger drain). */
    public static final double WALK_ON_WATER_ONE_PENALTY = 3.0;
    /** Multiplier favoring already-traversed nodes; 1.0 disables. */
    public static final double BACKTRACK_COST_FAVORING_COEFFICIENT = 0.5;
    /** Cancel + recompute the current movement if its live cost rises by this many ticks. */
    public static final double MAX_COST_INCREASE = 10.0;

    // ---- falling ----

    /** Max blocks to fall without a water bucket. */
    public static final int MAX_FALL_HEIGHT_NO_WATER = 3;
    /** Max blocks to fall when a water bucket can break the fall. */
    public static final int MAX_FALL_HEIGHT_BUCKET = 20;

    // ---- executor ----

    /** Cancel a movement if it overshoots its initial cost estimate by this many ticks. */
    public static final int MOVEMENT_TIMEOUT_TICKS = 100;
    /** Distance off-path (blocks) at which the away-timer starts counting. */
    public static final double MAX_DIST_FROM_PATH = 2.0;
    /** Distance off-path (blocks) that cancels immediately. */
    public static final double MAX_MAX_DIST_FROM_PATH = 3.0;
    /** Ticks of drift before giving up (~10 s). */
    public static final int MAX_TICKS_AWAY = 200;
    /** How many upcoming movements to re-verify the cost of each tick. */
    public static final int COST_VERIFICATION_LOOKAHEAD = 5;

    // ---- segmented planning ----

    /** Plan the next segment when the current has fewer than this many ticks left (7.5 s). */
    public static final int PLANNING_TICK_LOOKAHEAD = 150;
    /** Discard the last fraction of every computed path (avoid committing a stale tail). */
    public static final double PATH_CUTOFF_FACTOR = 0.9;
    /** Only apply the cutoff to paths at least this many movements long. */
    public static final int PATH_CUTOFF_MINIMUM_LENGTH = 30;

    // ---- behaviour toggles ----

    public static final boolean ALLOW_BREAK = true;
    public static final boolean ALLOW_PLACE = true;
    public static final boolean ALLOW_SPRINT = true;
    public static final boolean ALLOW_PARKOUR = false;
    public static final boolean ALLOW_PARKOUR_PLACE = false;
    public static final boolean ALLOW_PARKOUR_ASCEND = true;
    public static final boolean ALLOW_DIAGONAL_ASCEND = false;
    public static final boolean ALLOW_DIAGONAL_DESCEND = false;
    public static final boolean SPRINT_ASCENDS = true;
    public static final boolean ALLOW_DOWNWARD = true;

    // ---- mob avoidance (off by default; perf cost) ----

    public static final boolean AVOIDANCE = false;
    public static final double MOB_AVOIDANCE_COEFFICIENT = 1.5;
    public static final int MOB_AVOIDANCE_RADIUS = 8;
    public static final double MOB_SPAWNER_AVOIDANCE_COEFFICIENT = 2.0;
    public static final int MOB_SPAWNER_AVOIDANCE_RADIUS = 16;
}
