package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.CheckFurnaceTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code check_furnace} tool — read a furnace's working state without
 * walking to it. Returns whether it's lit, the input/fuel/output slot contents,
 * how many items are left to smelt, and a rough ETA. Cheap and remote: use it
 * to decide WHEN a smelt is done before sending collect_furnace to walk over.
 */
public final class CheckFurnaceTool implements AnimusTool {

    private static final long TIMEOUT_TICKS = 10 * 20;   // read-only; short

    @Override
    public String name() {
        return CheckFurnaceTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Check a furnace's progress at the given coordinates WITHOUT walking "
                + "there (cheap, remote). Returns lit state, input/fuel/output slot "
                + "contents, items left to smelt, and an approximate ETA. Use it to "
                + "know when a smelt you started with load_furnace is done, then call "
                + "collect_furnace to take the output. Coordinates come from the "
                + "load_furnace result.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("x", Map.of("type", "integer", "description", "Furnace x."));
        properties.put("y", Map.of("type", "integer", "description", "Furnace y."));
        properties.put("z", Map.of("type", "integer", "description", "Furnace z."));

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
        BlockPos pos = new BlockPos(requireInt(args, "x"), requireInt(args, "y"), requireInt(args, "z"));
        return new CheckFurnaceTaskRecord(toolCallId, currentGameTime + TIMEOUT_TICKS, pos);
    }

    private static int requireInt(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "argument '" + key + "' must be an integer: " + ex.getMessage());
        }
    }
}
