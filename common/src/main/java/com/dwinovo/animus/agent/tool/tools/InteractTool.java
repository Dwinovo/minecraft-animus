package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.InteractTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code interact} tool — the lowest-level native interaction: walk within
 * reach of a target, look at it, and press one mouse button. Everything else
 * (place_block, break_block, eat) is a convenience layer on top of this; reach
 * for {@code interact} when no dedicated tool fits — above all to ACTIVATE a
 * block (lever, button, door, trapdoor, bed, note block, crafting table, modded
 * machine GUI) or to right-click an entity (trade a villager, breed, mount, name).
 */
public final class InteractTool implements AnimusTool {

    private static final long TIMEOUT_TICKS = 30 * 20;   // covers walking to the target

    @Override
    public String name() {
        return InteractTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Lowest-level native interaction: travel to a target and press one "
                + "mouse button on it (auto-pathing there like move_to). Choose by "
                + "button:\n"
                + "• button=use (right-click): ACTIVATE a block (lever/button/door/"
                + "trapdoor/bed/note block/crafting table/modded GUI) with whatever "
                + "is in hand, or right-click an entity (trade villager / breed / "
                + "mount / name). Equip the needed item first if any.\n"
                + "• button=attack (left-click): break a block (native timed — a "
                + "pickaxe etc. must be in hand for stone) or hit an entity once.\n"
                + "Target EXACTLY one thing: a block via x,y,z (entity_id null) OR an "
                + "entity via entity_id (x,y,z null; ids come from scan_nearby_entities). "
                + "For routine placing/eating/mining prefer place_block/eat_item/"
                + "auto_mine; use interact for things those don't cover.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        // Exactly one target: block (x,y,z) XOR entity_id. Nullable rather than
        // absent so every key stays in `required` for strict structured output.
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("button", Map.of("type", "string", "enum", List.of("use", "attack"),
                "description", "use = right-click (activate/place/trade), attack = left-click (break/hit)."));
        properties.put("x", Map.of("type", List.of("integer", "null"),
                "description", "Block target X. Null when targeting an entity."));
        properties.put("y", Map.of("type", List.of("integer", "null"),
                "description", "Block target Y. Null when targeting an entity."));
        properties.put("z", Map.of("type", List.of("integer", "null"),
                "description", "Block target Z. Null when targeting an entity."));
        properties.put("entity_id", Map.of("type", List.of("integer", "null"),
                "description", "Entity target id (from scan_nearby_entities). Null when targeting a block."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("button", "x", "y", "z", "entity_id"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        InteractTaskRecord.Button button = readButton(args);
        Integer x = optionalInt(args, "x");
        Integer y = optionalInt(args, "y");
        Integer z = optionalInt(args, "z");
        Integer entityId = optionalInt(args, "entity_id");

        BlockPos block = null;
        if (x != null || y != null || z != null) {
            if (x == null || y == null || z == null) {
                throw new IllegalArgumentException(
                        "block target needs all of x, y, z (or leave all null to target an entity).");
            }
            block = new BlockPos(x, y, z);
        }
        // InteractTaskRecord enforces exactly-one-target.
        return new InteractTaskRecord(toolCallId, currentGameTime + TIMEOUT_TICKS,
                button, block, entityId);
    }

    private static InteractTaskRecord.Button readButton(JsonObject args) {
        if (!args.has("button") || args.get("button").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: button");
        }
        String b = args.get("button").getAsString();
        return switch (b) {
            case "use" -> InteractTaskRecord.Button.USE;
            case "attack" -> InteractTaskRecord.Button.ATTACK;
            default -> throw new IllegalArgumentException("button must be 'use' or 'attack', got: " + b);
        };
    }

    private static Integer optionalInt(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "argument '" + key + "' must be an integer or null: " + ex.getMessage());
        }
    }
}
