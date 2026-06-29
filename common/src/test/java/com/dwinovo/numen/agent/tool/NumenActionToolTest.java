package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.tool.tools.GetOwnerStatusTool;
import com.dwinovo.numen.agent.tool.tools.GetSelfStatusTool;
import com.dwinovo.numen.agent.tool.tools.GetWorldInfoTool;
import com.dwinovo.numen.agent.tool.tools.InspectBlockTool;
import com.dwinovo.numen.agent.tool.tools.MoveToTool;
import com.dwinovo.numen.agent.tool.tools.MovementTools;
import com.dwinovo.numen.agent.tool.tools.PerceptionTools;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.tasks.MoveToTaskRecord;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Zero-regression proof for the {@code @NumenAction} migrations: each reflected
 * tool must present the <em>identical</em> LLM-facing surface (name,
 * description, parameter schema) AND infer the <em>same</em> execution category
 * as the hand-written class it replaces, so tool selection and routing can't
 * shift. The originals are kept as oracles until a batch is migrated and
 * deleted together. Adding a migrated tool here is one line.
 */
class NumenActionToolTest {

    private static void assertSameSurface(NumenTool migrated, NumenTool original) {
        String id = original.name();
        assertEquals(original.name(), migrated.name(), "name");
        assertEquals(original.description(), migrated.description(), "description: " + id);
        assertEquals(original.parameterSchema(), migrated.parameterSchema(), "schema: " + id);
        // The adapter infers the category from the signature — it must land on
        // the same one the hand-written tool declared.
        assertEquals(original.isQuery(), migrated.isQuery(), "isQuery: " + id);
        assertEquals(original.isLocal(), migrated.isLocal(), "isLocal: " + id);
        assertEquals(original.isAsyncQuery(), migrated.isAsyncQuery(), "isAsyncQuery: " + id);
    }

    @Test
    void migratedToolsMatchOriginalSurface() {
        PerceptionTools perception = new PerceptionTools();
        MovementTools movement = new MovementTools();

        assertSameSurface(NumenTools.tool(perception, "get_self_status"), new GetSelfStatusTool());
        assertSameSurface(NumenTools.tool(perception, "inspect_block"), new InspectBlockTool());
        assertSameSurface(NumenTools.tool(perception, "get_owner_status"), new GetOwnerStatusTool());
        assertSameSurface(NumenTools.tool(perception, "get_world_info"), new GetWorldInfoTool());
        assertSameSurface(NumenTools.tool(movement, "move_to"), new MoveToTool());
    }

    @Test
    void moveToBuildsRecordFromCoercedArgs() {
        NumenTool migrated = NumenTools.tool(new MovementTools(), "move_to");

        // x + z, no y → a COLUMN goal; speed coerced; toolCallId + deadline injected.
        JsonObject args = new JsonObject();
        args.addProperty("x", 10.0);
        args.addProperty("z", 20.0);
        args.addProperty("speed", 1.5);
        // y intentionally absent → nullable, must coerce to null.

        TaskRecord rec = migrated.toTaskRecord("call-1", args, 1000L);
        MoveToTaskRecord move = assertInstanceOf(MoveToTaskRecord.class, rec);

        assertEquals(Double.valueOf(10.0), move.x);
        assertNull(move.y, "absent nullable arg must coerce to null");
        assertEquals(Double.valueOf(20.0), move.z);
        assertEquals(1.5, move.speed);
        assertEquals(MoveToTaskRecord.Kind.COLUMN, move.kind);
        assertEquals("call-1", move.getToolCallId());
        assertEquals(1000L + 30 * 20, move.getDeadlineGameTime(), "deadline = now + timeoutTicks");
    }
}
