package com.dwinovo.animus.pathing.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards that the cost model stays a 1:1 port of Baritone's {@code ActionCosts}
 * + the relevant {@code Settings} defaults (see {@code docs/BARITONE_PATHFINDING.md}).
 * Baritone's A* is intentionally inadmissible (weighted heuristic), so these
 * check the exact constants/formulas rather than any admissibility bound.
 */
class ActionCostsTest {

    @Test
    void baseCostsAreTwentyOverSpeed() {
        assertEquals(20.0 / 4.317, ActionCosts.WALK_ONE_BLOCK, 1e-9);
        assertEquals(20.0 / 5.612, ActionCosts.SPRINT_ONE_BLOCK, 1e-9);
        assertEquals(20.0 / 2.2, ActionCosts.WALK_ONE_IN_WATER, 1e-9);
        assertEquals(20.0 / 1.3, ActionCosts.SNEAK_ONE_BLOCK, 1e-9);
        assertEquals(20.0 / 2.35, ActionCosts.LADDER_UP_ONE, 1e-9);
        assertEquals(20.0 / 3.0, ActionCosts.LADDER_DOWN_ONE, 1e-9);
        assertEquals(ActionCosts.WALK_ONE_BLOCK * 0.8, ActionCosts.WALK_OFF_BLOCK, 1e-9);
        assertEquals(ActionCosts.WALK_ONE_BLOCK - ActionCosts.WALK_OFF_BLOCK,
                ActionCosts.CENTER_AFTER_FALL, 1e-9);
        assertEquals(ActionCosts.SPRINT_ONE_BLOCK / ActionCosts.WALK_ONE_BLOCK,
                ActionCosts.SPRINT_MULTIPLIER, 1e-9);
    }

    @Test
    void fallTableZeroAndMonotonic() {
        assertEquals(0.0, ActionCosts.fallCost(0));
        double prev = 0.0;
        for (int n = 1; n <= 16; n++) {
            double c = ActionCosts.fallCost(n);
            assertTrue(c > prev, "fallCost must grow with height (n=" + n + ")");
            prev = c;
        }
        // A 1-block fall under vanilla gravity (~0.5·g·t²≈1, g≈0.08) is on the
        // order of 5 ticks — a sanity band, not an exact assertion.
        assertTrue(ActionCosts.fallCost(1) > 4 && ActionCosts.fallCost(1) < 7,
                "fallCost(1) = " + ActionCosts.fallCost(1));
    }

    @Test
    void jumpAndDescendDerivedFromFallTable() {
        // JUMP_ONE_BLOCK = ticks(fall 1.25) − ticks(fall 0.25)
        assertEquals(ActionCosts.FALL_1_25_BLOCKS - ActionCosts.FALL_0_25_BLOCKS,
                ActionCosts.JUMP_ONE_BLOCK, 1e-9);
        assertTrue(ActionCosts.JUMP_ONE_BLOCK > 0);
        // GoalYLevel descends at FALL_N_BLOCKS_COST[2] / 2 per block.
        assertEquals(ActionCosts.fallCost(2) / 2.0, ActionCosts.DESCEND_ONE_BLOCK, 1e-9);
    }

    @Test
    void parkourCostMatchesBaritone() {
        // 2→walk×2, 3→walk×3, 4→sprint×4, + jumpPenalty
        assertEquals(ActionCosts.WALK_ONE_BLOCK * 2 + PathSettings.JUMP_PENALTY,
                ActionCosts.costFromJumpDistance(2), 1e-9);
        assertEquals(ActionCosts.WALK_ONE_BLOCK * 3 + PathSettings.JUMP_PENALTY,
                ActionCosts.costFromJumpDistance(3), 1e-9);
        assertEquals(ActionCosts.SPRINT_ONE_BLOCK * 4 + PathSettings.JUMP_PENALTY,
                ActionCosts.costFromJumpDistance(4), 1e-9);
        // gap must grow monotonically (a 4-gap uses the cheaper-per-block sprint cost)
        assertTrue(ActionCosts.costFromJumpDistance(3) > ActionCosts.costFromJumpDistance(2));
        assertTrue(ActionCosts.costFromJumpDistance(4) > ActionCosts.costFromJumpDistance(3));
    }

    @Test
    void settingsMatchBaritoneDefaults() {
        assertEquals(3.563, PathSettings.COST_HEURISTIC, 1e-9);
        assertEquals(20.0, PathSettings.BLOCK_PLACEMENT_PENALTY, 1e-9);
        assertEquals(2.0, PathSettings.BLOCK_BREAK_ADDITIONAL_PENALTY, 1e-9);
        assertEquals(2.0, PathSettings.JUMP_PENALTY, 1e-9);
        assertEquals(100, PathSettings.MOVEMENT_TIMEOUT_TICKS);
        assertEquals(200, PathSettings.MAX_TICKS_AWAY);
        assertEquals(3, PathSettings.MAX_FALL_HEIGHT_NO_WATER);
        assertEquals(30, PathSettings.PATH_CUTOFF_MINIMUM_LENGTH);
    }
}
