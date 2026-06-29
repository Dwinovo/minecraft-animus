package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.tool.tools.GetSelfStatusTool;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zero-regression proofs for the {@code @NumenAction} migrations: each reflected
 * tool must present the <em>identical</em> LLM-facing surface (name, description,
 * parameter schema) as the hand-written class it replaces, so tool selection
 * can't shift. The originals are kept as oracles until a batch is migrated and
 * deleted together. {@code move_to} additionally verifies the runtime build of
 * its task record (arg coercion + nullable handling + ToolContext injection).
 */
class NumenActionToolTest {

    private static void assertSameSurface(NumenTool migrated, NumenTool original) {
        assertEquals(original.name(), migrated.name());
        assertEquals(original.description(), migrated.description());
        assertEquals(original.parameterSchema(), migrated.parameterSchema(),
                "auto-derived schema must equal the hand-written one for " + original.name());
    }

    @Test
    void getSelfStatusMatchesOriginalSurface() {
        NumenTool migrated = NumenTools.tool(new PerceptionTools(), "get_self_status");
        assertSameSurface(migrated, new GetSelfStatusTool());
        assertTrue(migrated.isQuery(), "get_self_status must stay a server query");
    }

    @Test
    void inspectBlockMatchesOriginalSurface() {
        NumenTool migrated = NumenTools.tool(new PerceptionTools(), "inspect_block");
        assertSameSurface(migrated, new InspectBlockTool());
        assertTrue(migrated.isQuery(), "inspect_block must stay a server query");
    }

    @Test
    void moveToMatchesOriginalSurface() {
        NumenTool migrated = NumenTools.tool(new MovementTools(), "move_to");
        assertSameSurface(migrated, new MoveToTool());
        assertEquals(new MoveToTool().defaultTimeoutTicks(), migrated.defaultTimeoutTicks());
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
