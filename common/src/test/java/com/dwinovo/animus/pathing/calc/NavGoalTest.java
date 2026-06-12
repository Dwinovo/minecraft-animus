package com.dwinovo.animus.pathing.calc;

import com.dwinovo.animus.pathing.util.ActionCosts;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The goal object is what the search terminates on AND aims with — a wrong
 * isAt strands tasks, an inadmissible heuristic silently degrades paths.
 */
class NavGoalTest {

    private static final BlockPos GOAL = new BlockPos(10, 64, 10);

    @Test
    void exactAcceptsOnlyTheCell() {
        NavGoal g = NavGoal.exact(GOAL);
        assertTrue(g.isAt(new BlockPos(10, 64, 10)));
        assertFalse(g.isAt(new BlockPos(11, 64, 10)));
        assertEquals(GOAL, g.center());
    }

    @Test
    void nearAcceptsTheSphereAndStaysAdmissible() {
        NavGoal g = NavGoal.near(GOAL, 2.0);
        assertTrue(g.isAt(new BlockPos(11, 64, 10)));
        assertTrue(g.isAt(new BlockPos(10, 65, 11)));
        assertFalse(g.isAt(new BlockPos(13, 64, 10)));
        // Inside the radius the remaining cost may legitimately be zero.
        assertEquals(0.0, g.heuristic(new BlockPos(11, 64, 10)));
        // Outside, the bound must never exceed the point bound (admissibility
        // slack for stopping early), and never go negative.
        BlockPos far = new BlockPos(40, 64, 10);
        assertTrue(g.heuristic(far) <= NavGoal.pointBound(GOAL, far));
        assertTrue(g.heuristic(far) >= 0.0);
    }

    @Test
    void adjacentAcceptsTheFourSidesWithinOneY() {
        NavGoal g = NavGoal.adjacent(GOAL);
        assertTrue(g.isAt(new BlockPos(11, 64, 10)));
        assertTrue(g.isAt(new BlockPos(9, 65, 10)));
        assertTrue(g.isAt(new BlockPos(10, 63, 11)));
        assertFalse(g.isAt(GOAL), "the target's own cell is not a stance");
        assertFalse(g.isAt(new BlockPos(11, 64, 11)), "diagonals are not adjacent");
        assertFalse(g.isAt(new BlockPos(12, 64, 10)));
    }

    @Test
    void pointBoundIsConsistentWithMoveCosts() {
        // One flat step: exactly one walk. One step up: walk + jump.
        assertEquals(ActionCosts.WALK_ONE_BLOCK,
                NavGoal.pointBound(GOAL, new BlockPos(9, 64, 10)), 1e-9);
        assertEquals(ActionCosts.WALK_ONE_BLOCK + ActionCosts.JUMP_ONE_BLOCK,
                NavGoal.pointBound(GOAL, new BlockPos(9, 63, 10)), 1e-9);
        // Descending must cost something but stay a lower bound.
        double down = NavGoal.pointBound(GOAL, new BlockPos(10, 67, 10));
        assertEquals(3 * ActionCosts.DESCEND_ONE_BLOCK, down, 1e-9);
    }
}
