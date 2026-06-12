package com.dwinovo.animus.task.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** +X is east, +Z is south — the labels feed straight into LLM-facing results. */
class CompassUtilTest {

    @Test
    void cardinals() {
        assertEquals("north", CompassUtil.compass(0, -10));
        assertEquals("south", CompassUtil.compass(0, 10));
        assertEquals("east", CompassUtil.compass(10, 0));
        assertEquals("west", CompassUtil.compass(-10, 0));
    }

    @Test
    void diagonals() {
        assertEquals("north-east", CompassUtil.compass(10, -10));
        assertEquals("north-west", CompassUtil.compass(-10, -10));
        assertEquals("south-east", CompassUtil.compass(10, 10));
        assertEquals("south-west", CompassUtil.compass(-10, 10));
    }

    @Test
    void dominantAxisCollapsesToCardinal() {
        // |dz| >= 2|dx| → pure north/south; same rule mirrored for east/west.
        assertEquals("north", CompassUtil.compass(5, -10));
        assertEquals("east", CompassUtil.compass(10, 4));
        // Just inside the diagonal band stays diagonal.
        assertEquals("south-east", CompassUtil.compass(10, 6));
    }

    @Test
    void originIsHere() {
        assertEquals("here", CompassUtil.compass(0, 0));
    }
}
