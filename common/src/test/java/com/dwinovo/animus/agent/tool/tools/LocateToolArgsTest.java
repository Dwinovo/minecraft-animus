package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.task.tasks.LocateBiomeTaskRecord;
import com.dwinovo.animus.task.tasks.LocateStructureTaskRecord;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tool-arg validation is the first line of the "teach the model" contract:
 * malformed calls must throw (the loop reports them back as failed results)
 * and valid calls must stamp the right deadline.
 */
class LocateToolArgsTest {

    private static JsonObject obj(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    @Test
    void locateStructureBuildsRecordWithDeadline() {
        var record = (LocateStructureTaskRecord) new LocateStructureTool()
                .toTaskRecord("call_1", obj("structure", " minecraft:fortress "), 1000L);
        assertEquals("minecraft:fortress", record.structure);
        assertEquals(1000L + 30 * 20, record.getDeadlineGameTime());
    }

    @Test
    void locateBiomeBuildsRecordWithDeadline() {
        var record = (LocateBiomeTaskRecord) new LocateBiomeTool()
                .toTaskRecord("call_1", obj("biome", "minecraft:warped_forest"), 50L);
        assertEquals("minecraft:warped_forest", record.biome);
        assertEquals(50L + 30 * 20, record.getDeadlineGameTime());
    }

    @Test
    void missingOrBlankArgsThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                new LocateStructureTool().toTaskRecord("c", new JsonObject(), 0L));
        assertThrows(IllegalArgumentException.class, () ->
                new LocateStructureTool().toTaskRecord("c", obj("structure", "   "), 0L));
        assertThrows(IllegalArgumentException.class, () ->
                new LocateBiomeTool().toTaskRecord("c", new JsonObject(), 0L));
        assertThrows(IllegalArgumentException.class, () ->
                new LocateBiomeTool().toTaskRecord("c", obj("biome", ""), 0L));
    }

    @Test
    void oversizedArgThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new LocateBiomeTool().toTaskRecord("c", obj("biome", "x".repeat(200)), 0L));
    }
}
