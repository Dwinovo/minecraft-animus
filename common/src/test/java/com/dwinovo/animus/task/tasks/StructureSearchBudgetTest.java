package com.dwinovo.animus.task.tasks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The global per-tick budget is the "never stalls the server" promise of both
 * locate tools: drained pools must refuse work, a new tick must replenish,
 * and the three pools must be independent.
 */
class StructureSearchBudgetTest {

    @BeforeEach
    void freshTick() {
        StructureSearchBudget.resetForTick(1);
    }

    @Test
    void checkPoolDrainsAt128() {
        for (int i = 0; i < 128; i++) {
            assertTrue(StructureSearchBudget.tryCheck(), "permit " + i + " should grant");
        }
        assertFalse(StructureSearchBudget.tryCheck(), "pool must be dry after 128");
    }

    @Test
    void chunkLoadPoolDrainsAt2() {
        assertTrue(StructureSearchBudget.tryChunkLoad());
        assertTrue(StructureSearchBudget.tryChunkLoad());
        assertFalse(StructureSearchBudget.tryChunkLoad());
    }

    @Test
    void biomeSamplePoolDrainsAt256() {
        for (int i = 0; i < 256; i++) {
            assertTrue(StructureSearchBudget.tryBiomeSample(), "permit " + i + " should grant");
        }
        assertFalse(StructureSearchBudget.tryBiomeSample());
    }

    @Test
    void poolsAreIndependent() {
        while (StructureSearchBudget.tryCheck()) { /* drain checks */ }
        assertTrue(StructureSearchBudget.tryChunkLoad(), "chunk loads survive check drain");
        assertTrue(StructureSearchBudget.tryBiomeSample(), "biome samples survive check drain");
    }

    @Test
    void newTickReplenishes() {
        while (StructureSearchBudget.tryChunkLoad()) { /* drain */ }
        StructureSearchBudget.resetForTick(2);
        assertTrue(StructureSearchBudget.tryChunkLoad());
    }
}
