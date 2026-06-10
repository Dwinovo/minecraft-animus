package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.MoveToTaskRecord;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code move_to} tool — first concrete LLM-facing action in the mod.
 * The typed payload it emits is consumed by
 * {@link com.dwinovo.animus.task.tasks.MoveToTaskGoal}, which drives the custom
 * terrain-modifying pathfinder ({@link com.dwinovo.animus.pathing}).
 *
 * <h2>Schema</h2>
 * <pre>
 * {
 *   "type": "object",
 *   "properties": {
 *     "x": { "type": "number" },
 *     "y": { "type": "number" },
 *     "z": { "type": "number" },
 *     "speed": { "type": "number", "minimum": 0.1, "maximum": 2.0 }
 *   },
 *   "required": ["x", "y", "z", "speed"],
 *   "additionalProperties": false
 * }
 * </pre>
 *
 * <p>{@code speed} is required (not optional) because OpenAI strict mode +
 * structured outputs demand {@code required} list every {@code properties}
 * key. The model will fill it with 1.0 if not relevant — acceptable cost.
 */
public final class MoveToTool implements AnimusTool {

    /** Base budget: 30 seconds at vanilla 20 tps. {@code MoveToTaskGoal.onStart}
     *  extends this by journey distance (~1s/block, capped) — the tool layer
     *  can't scale it here because it doesn't know the entity's position. */
    private static final long DEFAULT_TIMEOUT_TICKS = 30 * 20;

    private static final double MIN_SPEED = 0.1;
    private static final double MAX_SPEED = 2.0;

    @Override
    public String name() {
        return MoveToTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Travel to the given world coordinates — full terrain-traversing "
                + "navigation, not just walking. En route it automatically mines "
                + "through obstructions, digs straight down or up, bridges gaps and "
                + "pillars upward with cobblestone/dirt from inventory, and jumps "
                + "small gaps. To descend to a mining depth, just move_to(x, "
                + "targetY, z) — no need to mine_block your way down. Consumes "
                + "scaffold blocks and tool durability en route; carry cobblestone "
                + "or dirt for terrain that needs bridging. The timeout scales with "
                + "distance; if it still times out it reports the progress made "
                + "(blocks dug/placed, final position) — call move_to again with "
                + "the same target to resume from where it stopped. Returns success "
                + "when within roughly 2 blocks of the target.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("x", Map.of("type", "number",
                "description", "Target world X coordinate."));
        properties.put("y", Map.of("type", "number",
                "description", "Target world Y coordinate (block height)."));
        properties.put("z", Map.of("type", "number",
                "description", "Target world Z coordinate."));
        properties.put("speed", Map.of("type", "number",
                "description", "Speed multiplier in [0.1, 2.0]. 1.0 is normal walking speed.",
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
        double x = requireDouble(args, "x");
        double y = requireDouble(args, "y");
        double z = requireDouble(args, "z");
        double speed = requireDouble(args, "speed");
        if (speed < MIN_SPEED) speed = MIN_SPEED;
        if (speed > MAX_SPEED) speed = MAX_SPEED;
        long deadline = currentGameTime + DEFAULT_TIMEOUT_TICKS;
        return new MoveToTaskRecord(toolCallId, deadline, x, y, z, speed);
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
