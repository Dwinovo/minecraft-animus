package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.CollectFurnaceTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code collect_furnace} tool — walk to the furnace at the given
 * coordinates and take its finished output into the entity's inventory. Returns
 * what was collected plus the furnace's remaining state (so you also learn how
 * much is still smelting). Coordinates come from a prior load_furnace result;
 * use check_furnace first if you want to confirm it's done before walking over.
 */
public final class CollectFurnaceTool implements AnimusTool {

    private static final long TIMEOUT_TICKS = 30 * 20;   // covers walking to the furnace

    @Override
    public String name() {
        return CollectFurnaceTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Walk to the furnace at the given coordinates and take its finished "
                + "output into your inventory. Returns how much was collected and the "
                + "furnace's remaining state (items still smelting, output left if "
                + "your inventory was full). Coordinates come from the load_furnace "
                + "result. If nothing is ready yet it reports that — use check_furnace "
                + "first to avoid walking over too early.";
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
        return new CollectFurnaceTaskRecord(toolCallId, currentGameTime + TIMEOUT_TICKS, pos);
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
