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
        return "Travel somewhere — full terrain-traversing navigation, not just "
                + "walking. Pick ONE of three intents by which coordinates you "
                + "fill (leave the others null):\n"
                + "• Go to a LOCATION: give x and z, leave y null. The companion "
                + "walks to that spot and stands on whatever ground is there — Y is "
                + "auto-resolved to the surface. THIS IS THE DEFAULT for 'go over "
                + "there' / following / exploring; never guess a Y for a location.\n"
                + "• Go to an EXACT cell: give x, y and z. Only for a specific cell "
                + "you know is reachable (e.g. a block you scanned). If that cell is "
                + "mid-air or walled in it will report it couldn't reach it.\n"
                + "• Change ELEVATION: give y only (x and z null) to climb to the "
                + "surface or descend to a mining depth at your current column.\n"
                + "En route it mines through obstructions, digs down/up, bridges "
                + "gaps and pillars up with cobblestone/dirt from inventory. Digging "
                + "is gated by your HELD tool: stone/deepslate need a pickaxe IN "
                + "HAND (equip_item first); a sword held makes stone an impassable "
                + "wall. Consumes scaffold blocks and tool durability; carry "
                + "cobblestone/dirt for gaps. Timeout scales with distance; the "
                + "result reports the actual position reached (and the real ground "
                + "height) — call again with the same target to resume.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        // x/y/z are nullable (type union with "null") rather than absent: strict
        // structured-output mode requires every property to appear in `required`,
        // so "omitted" is expressed as an explicit null. The combination of which
        // are non-null selects the goal (location / exact cell / elevation).
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("x", Map.of("type", List.of("number", "null"),
                "description", "Target X. Null for an elevation-only move (y alone)."));
        properties.put("y", Map.of("type", List.of("number", "null"),
                "description", "Target Y (block height). LEAVE NULL to go to a "
                        + "location (x+z) — Y is auto-resolved to the surface. Only "
                        + "set it for an exact cell (x+y+z) or an elevation move (y alone)."));
        properties.put("z", Map.of("type", List.of("number", "null"),
                "description", "Target Z. Null for an elevation-only move (y alone)."));
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
        Double x = optionalDouble(args, "x");
        Double y = optionalDouble(args, "y");
        Double z = optionalDouble(args, "z");
        double speed = requireDouble(args, "speed");
        if (speed < MIN_SPEED) speed = MIN_SPEED;
        if (speed > MAX_SPEED) speed = MAX_SPEED;
        long deadline = currentGameTime + DEFAULT_TIMEOUT_TICKS;
        // MoveToTaskRecord validates the x/y/z combination and throws a teaching
        // error for an ambiguous one (e.g. only x given).
        return new MoveToTaskRecord(toolCallId, deadline, x, y, z, speed);
    }

    /** A nullable numeric arg: {@code null} when absent or JSON null. */
    private static Double optionalDouble(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsDouble();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "argument '" + key + "' must be a number or null: " + ex.getMessage());
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
