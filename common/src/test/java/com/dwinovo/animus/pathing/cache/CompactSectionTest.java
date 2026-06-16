package com.dwinovo.animus.pathing.cache;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic checks for {@link CompactSection}'s bit-packing math (no MC bootstrap needed). */
class CompactSectionTest {

    @Test
    void bitsFor_isCeilLog2_atLeastOne() {
        // A uniform / empty palette still needs 1 bit (SimpleBitStorage requires ≥1, index 0 exists).
        assertEquals(1, CompactSection.bitsFor(0));
        assertEquals(1, CompactSection.bitsFor(1));
        assertEquals(1, CompactSection.bitsFor(2));   // index 0..1
        assertEquals(2, CompactSection.bitsFor(3));   // index 0..2
        assertEquals(2, CompactSection.bitsFor(4));   // index 0..3
        assertEquals(3, CompactSection.bitsFor(5));
        assertEquals(3, CompactSection.bitsFor(8));
        assertEquals(4, CompactSection.bitsFor(9));
        assertEquals(4, CompactSection.bitsFor(16));
        assertEquals(8, CompactSection.bitsFor(256));
        assertEquals(9, CompactSection.bitsFor(257));
    }

    @Test
    void bitsFor_alwaysRepresentsTopIndex() {
        // Whatever bit width we pick must hold the largest palette index (size - 1).
        for (int size = 1; size <= 4096; size++) {
            int bits = CompactSection.bitsFor(size);
            assertTrue((size - 1) < (1L << bits),
                    "size=" + size + " top index " + (size - 1) + " doesn't fit in " + bits + " bits");
        }
    }

    @Test
    void index_isBijectiveOver16Cubed() {
        Set<Integer> seen = new HashSet<>();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int i = CompactSection.index(x, y, z);
                    assertTrue(i >= 0 && i < 4096, "index out of range: " + i);
                    assertTrue(seen.add(i), "duplicate index " + i + " at " + x + "," + y + "," + z);
                }
            }
        }
        assertEquals(4096, seen.size());
    }
}
