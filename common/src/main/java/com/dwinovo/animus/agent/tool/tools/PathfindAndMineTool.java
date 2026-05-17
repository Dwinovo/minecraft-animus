package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.PathfindAndMineTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code pathfind_and_mine} tool — single op that walks to a block and
 * then mines it. Pre-checks mineability so the entity never wastes a walk
 * onto bedrock or air. Use this when the LLM would otherwise emit
 * {@code move_to → mine_block} as two consecutive turns.
 *
 * <h2>Schema</h2>
 * <pre>
 * {
 *   "type": "object",
 *   "properties": {
 *     "x": { "type": "integer" },
 *     "y": { "type": "integer" },
 *     "z": { "type": "integer" },
 *     "speed": { "type": "number", "minimum": 0.1, "maximum": 2.0 }
 *   },
 *   "required": ["x", "y", "z", "speed"],
 *   "additionalProperties": false
 * }
 * </pre>
 *
 * <h2>When to prefer this over {@code mine_block}</h2>
 * Use this when the target is not already in reach and you'd otherwise
 * compose two tool calls. Use {@code mine_block} when you're already
 * adjacent (e.g. chain-mining inside a tunnel, where the prior
 * {@code mine_block} already left you in range of the next).
 */
public final class PathfindAndMineTool implements AnimusTool {

    /** 90s at 20 tps — covers a moderate walk + a slow dig (obsidian-with-iron-pickaxe ≈ 10s). */
    private static final long DEFAULT_TIMEOUT_TICKS = 90 * 20;

    private static final double MIN_SPEED = 0.1;
    private static final double MAX_SPEED = 2.0;

    @Override
    public String name() {
        return PathfindAndMineTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Walk to the given block then mine it. Pre-checks the block is "
                + "actually mineable (skips walking to bedrock or air). "
                + "Resolves on: block broken (success), block unmineable "
                + "(immediate failure), path unreachable / stuck (failure), or "
                + "90-second timeout. Use this instead of move_to + mine_block "
                + "when the target is out of reach.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("x", Map.of("type", "integer",
                "description", "Target block X coordinate."));
        properties.put("y", Map.of("type", "integer",
                "description", "Target block Y coordinate."));
        properties.put("z", Map.of("type", "integer",
                "description", "Target block Z coordinate."));
        properties.put("speed", Map.of("type", "number",
                "description", "Walking speed multiplier in [0.1, 2.0]. 1.0 is normal speed.",
                "minimum", MIN_SPEED,
                "maximum", MAX_SPEED));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("x", "y", "z", "speed"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return DEFAULT_TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        int x = requireInt(args, "x");
        int y = requireInt(args, "y");
        int z = requireInt(args, "z");
        double speed = requireDouble(args, "speed");
        if (speed < MIN_SPEED) speed = MIN_SPEED;
        if (speed > MAX_SPEED) speed = MAX_SPEED;
        long deadline = currentGameTime + DEFAULT_TIMEOUT_TICKS;
        return new PathfindAndMineTaskRecord(toolCallId, deadline, new BlockPos(x, y, z), speed);
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

    private static double requireDouble(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        try {
            return args.get(key).getAsDouble();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "argument '" + key + "' must be a number: " + ex.getMessage());
        }
    }
}
