package com.dwinovo.animus.pathing.calc;

import com.dwinovo.animus.pathing.util.ActionCosts;
import com.dwinovo.animus.pathing.util.PathSettings;
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
    void nearAcceptsTheSphereWithFullBlockHeuristic() {
        NavGoal g = NavGoal.near(GOAL, 2.0);
        assertTrue(g.isAt(new BlockPos(11, 64, 10)));
        assertTrue(g.isAt(new BlockPos(10, 65, 11)));
        assertFalse(g.isAt(new BlockPos(13, 64, 10)));
        // Baritone GoalNear.heuristic IS the full GoalBlock.calculate — the radius only
        // relaxes isInGoal, it is NOT subtracted from the aim (so it can be non-zero even
        // for an in-radius node, and equals the point bound everywhere).
        for (BlockPos p : new BlockPos[]{
                new BlockPos(11, 64, 10), new BlockPos(40, 64, 10), new BlockPos(10, 70, 10)}) {
            assertEquals(NavGoal.pointBound(GOAL, p), g.heuristic(p));
        }
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
    void pointBoundMatchesBaritoneGoalShape() {
        // Baritone GoalXZ weights each flat block by costHeuristic (the heap key
        // adds no further multiplier). One flat step = costHeuristic.
        assertEquals(PathSettings.COST_HEURISTIC,
                NavGoal.pointBound(GOAL, new BlockPos(9, 64, 10)), 1e-9);
        // One flat + one up = costHeuristic + jump (GoalYLevel up term).
        assertEquals(PathSettings.COST_HEURISTIC + ActionCosts.JUMP_ONE_BLOCK,
                NavGoal.pointBound(GOAL, new BlockPos(9, 63, 10)), 1e-9);
        // Three blocks of descent = 3 × DESCEND (GoalYLevel down term, fall[2]/2).
        double down = NavGoal.pointBound(GOAL, new BlockPos(10, 67, 10));
        assertEquals(3 * ActionCosts.DESCEND_ONE_BLOCK, down, 1e-9);
    }
}
