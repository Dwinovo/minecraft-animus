package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.MineBlockTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code mine_block} tool — break a single block within vanilla
 * interaction reach (≈4.5 blocks). Mining time follows vanilla's formula
 * based on the block's hardness and whatever the entity is holding.
 *
 * <h2>Schema</h2>
 * <pre>
 * {
 *   "type": "object",
 *   "properties": {
 *     "x": { "type": "integer" },
 *     "y": { "type": "integer" },
 *     "z": { "type": "integer" }
 *   },
 *   "required": ["x", "y", "z"],
 *   "additionalProperties": false
 * }
 * </pre>
 *
 * <h2>Atomic by design (no built-in pathing)</h2>
 * If the target is out of reach the call fails with {@code "out of reach"} —
 * the LLM should then compose {@code move_to(x, y, z) → mine_block(x, y, z)}.
 * This mirrors Mineflayer's {@code bot.dig} / {@code bot.pathfinder.goto}
 * split and keeps every failure attributable to one clear cause.
 *
 * <h2>Drops</h2>
 * Vanilla loot table runs with the entity as the breaker, so silk-touch /
 * fortune from a future tool slot will work without extra wiring. Mining a
 * block whose required tool tier you don't have still succeeds (block
 * breaks) but yields no drop — same as a barehand player on stone.
 */
public final class MineBlockTool implements AnimusTool {

    /** 60s at 20 tps. Even obsidian-with-iron-pickaxe finishes inside this. */
    private static final long DEFAULT_TIMEOUT_TICKS = 60 * 20;

    @Override
    public String name() {
        return MineBlockTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Mine (break) a single block at the given integer coordinates. "
                + "Requires the block to be within ~4.5 blocks of the entity — "
                + "if it's farther, the call fails immediately with 'out of "
                + "reach' and you should call move_to first. Mining time depends "
                + "on block hardness and whatever the entity is holding (the "
                + "loot table behaves exactly like a player using that item).";
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

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("x", "y", "z"));
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
        long deadline = currentGameTime + DEFAULT_TIMEOUT_TICKS;
        return new MineBlockTaskRecord(toolCallId, deadline, new BlockPos(x, y, z));
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
