package com.dwinovo.numen.agent.tool.tools;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.task.tasks.LocateBiomeTaskRecord;
import com.dwinovo.numen.task.tasks.LocateStructureTaskRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The locate holders' own arg validation (trim + empty/length) and deadline
 * stamping, tested directly against the {@link LocateTools} methods (generic
 * missing-arg coercion is the adapter's job, covered elsewhere).
 */
class LocateToolArgsTest {

    private static ToolContext ctx(long gameTime) {
        return new ToolContext("call", gameTime);
    }

    @Test
    void locateStructureBuildsRecordWithDeadline() {
        var record = (LocateStructureTaskRecord) new LocateTools()
                .locateStructure(" minecraft:fortress ", ctx(1000L));
        assertEquals("minecraft:fortress", record.structure);
        assertEquals(1000L + 30 * 20, record.getDeadlineGameTime());
    }

    @Test
    void locateBiomeBuildsRecordWithDeadline() {
        var record = (LocateBiomeTaskRecord) new LocateTools()
                .locateBiome("minecraft:warped_forest", ctx(50L));
        assertEquals("minecraft:warped_forest", record.biome);
        assertEquals(50L + 30 * 20, record.getDeadlineGameTime());
    }

    @Test
    void blankOrOversizedArgsThrow() {
        LocateTools locate = new LocateTools();
        assertThrows(IllegalArgumentException.class, () -> locate.locateStructure("   ", ctx(0L)));
        assertThrows(IllegalArgumentException.class, () -> locate.locateBiome("", ctx(0L)));
        assertThrows(IllegalArgumentException.class, () -> locate.locateBiome("x".repeat(200), ctx(0L)));
    }
}
