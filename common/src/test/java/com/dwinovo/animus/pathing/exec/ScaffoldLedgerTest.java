package com.dwinovo.animus.pathing.exec;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger is what stops auto_mine from eating the pet's own bridge — a
 * false negative strands it over water, a stuck stale entry makes a natural
 * deposit unminable forever (hence self-heal).
 */
class ScaffoldLedgerTest {

    private static final String COBBLE = "cobblestone";
    private static final String DIRT = "dirt";

    @Test
    void recordedPositionIsProtectedWhileBlockMatches() {
        ScaffoldLedger<String> ledger = new ScaffoldLedger<>();
        ledger.record(new BlockPos(1, 64, 1), COBBLE);
        assertTrue(ledger.isOwnScaffold(new BlockPos(1, 64, 1), COBBLE));
        // Still protected on repeat queries — matching reads must not evict.
        assertTrue(ledger.isOwnScaffold(new BlockPos(1, 64, 1), COBBLE));
    }

    @Test
    void worldChangeSelfHealsTheEntry() {
        ScaffoldLedger<String> ledger = new ScaffoldLedger<>();
        BlockPos pos = new BlockPos(5, 70, -3);
        ledger.record(pos, COBBLE);
        // Someone replaced the bridge block with dirt: no longer ours.
        assertFalse(ledger.isOwnScaffold(pos, DIRT));
        // And the stale entry is gone — even the original block type is now
        // treated as natural terrain.
        assertFalse(ledger.isOwnScaffold(pos, COBBLE));
        assertEquals(0, ledger.size());
    }

    @Test
    void unknownPositionsAreNotProtected() {
        ScaffoldLedger<String> ledger = new ScaffoldLedger<>();
        assertFalse(ledger.isOwnScaffold(new BlockPos(0, 0, 0), COBBLE));
    }

    @Test
    void capacityEvictsOldestFirst() {
        ScaffoldLedger<String> ledger = new ScaffoldLedger<>();
        for (int i = 0; i < 600; i++) {
            ledger.record(new BlockPos(i, 64, 0), COBBLE);
        }
        assertEquals(512, ledger.size());
        assertFalse(ledger.isOwnScaffold(new BlockPos(0, 64, 0), COBBLE),
                "oldest entries must be evicted");
        assertTrue(ledger.isOwnScaffold(new BlockPos(599, 64, 0), COBBLE),
                "newest entries must survive");
    }
}
