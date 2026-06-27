package com.dwinovo.numen.agent.tool.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.tasks.BreakBlockTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code break_block} tool — surgical removal of one exact cell. The
 * inverse of {@code place_block}: construction needs both "put a block HERE"
 * and "remove the block HERE" (clear the cell a portal frame must occupy,
 * prune the leaves blocking a placement, undo a misplace). {@code auto_mine}
 * can't do this — it gathers by TYPE and picks its own targets.
 */
public final class BreakBlockTool implements NumenTool {

    /** Walk + dig budget; obsidian by hand-tier diamond pick is ~10s alone. */
    private static final long TIMEOUT_TICKS = 45 * 20;

    @Override
    public String name() {
        return BreakBlockTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Break the ONE block at exact coordinates — the precision inverse "
                + "of place_block, for construction work: clear the cell a "
                + "structure block must occupy, remove a block placed by mistake, "
                + "prune obstructions. The entity walks within reach first. "
                + "Drops are collected into your inventory. Requires the right "
                + "tool in hand for blocks that need one (same rule as "
                + "auto_mine — stone needs a pickaxe); fails with guidance "
                + "otherwise. To GATHER resources by type, use auto_mine "
                + "instead — it finds blocks itself.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("x", Map.of("type", "integer", "description", "Block X."));
        properties.put("y", Map.of("type", "integer", "description", "Block Y."));
        properties.put("z", Map.of("type", "integer", "description", "Block Z."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("x", "y", "z"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        BlockPos target = new BlockPos(
                ToolArgs.requireInt(args, "x"), ToolArgs.requireInt(args, "y"), ToolArgs.requireInt(args, "z"));
        return new BreakBlockTaskRecord(toolCallId, currentGameTime + TIMEOUT_TICKS, target);
    }
}
