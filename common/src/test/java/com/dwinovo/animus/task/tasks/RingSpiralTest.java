package com.dwinovo.animus.task.tasks;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spiral is the candidate enumerator for both locate tools — a missed or
 * duplicated cell means a structure/biome that can never be found (the exact
 * bug class behind "fortress search always comes back empty").
 */
class RingSpiralTest {

    @Test
    void ringZeroIsTheSingleCenterCell() {
        assertEquals(1, RingSpiral.perimeter(0));
        int[] d = RingSpiral.offset(0, 0);
        assertEquals(0, d[0]);
        assertEquals(0, d[1]);
    }

    @Test
    void perimeterGrowsByEightPerRing() {
        assertEquals(8, RingSpiral.perimeter(1));
        assertEquals(16, RingSpiral.perimeter(2));
        assertEquals(800, RingSpiral.perimeter(100));
    }

    @Test
    void everyRingCellSitsExactlyOnItsRing() {
        for (int ring = 1; ring <= 6; ring++) {
            for (int idx = 0; idx < RingSpiral.perimeter(ring); idx++) {
                int[] d = RingSpiral.offset(ring, idx);
                int chebyshev = Math.max(Math.abs(d[0]), Math.abs(d[1]));
                assertEquals(ring, chebyshev,
                        "ring " + ring + " idx " + idx + " landed at distance " + chebyshev);
            }
        }
    }

    @Test
    void ringsZeroToNCoverTheFullSquareWithoutDuplicates() {
        int n = 5;
        Set<Long> seen = new HashSet<>();
        for (int ring = 0; ring <= n; ring++) {
            for (int idx = 0; idx < RingSpiral.perimeter(ring); idx++) {
                int[] d = RingSpiral.offset(ring, idx);
                long key = ((long) d[0] << 32) ^ (d[1] & 0xFFFFFFFFL);
                assertTrue(seen.add(key),
                        "duplicate cell (" + d[0] + "," + d[1] + ") on ring " + ring);
            }
        }
        int side = 2 * n + 1;
        assertEquals(side * side, seen.size(), "spiral must tile the full square");
    }
}
