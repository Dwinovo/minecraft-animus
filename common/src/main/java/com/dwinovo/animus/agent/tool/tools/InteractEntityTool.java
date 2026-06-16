package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.InteractEntityTaskRecord;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code interact_entity} — the entity-aimed native interaction (the ENTITY column of vanilla's
 * mouse input). Auto-paths AND follows the live (moving) entity, then presses a mouse button on
 * it once the crosshair actually reaches it.
 */
public final class InteractEntityTool implements AnimusTool {

    private static final long TIMEOUT_TICKS = 60 * 20;   // covers chasing a moving target

    @Override
    public String name() {
        return InteractEntityTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Press a mouse button on an ENTITY — the native interaction for moving targets. "
                + "Auto-paths and FOLLOWS the entity (id from scan_nearby_entities), then acts once "
                + "the crosshair reaches it (a wall in the way makes it re-position, not hit "
                + "through).\n"
                + "• button=left (attack): hit it. hold_ticks=-1 keeps hitting until it dies "
                + "(killing a mob); 0 = a single hit.\n"
                + "• button=right (use): interact with the held item — trade a villager, breed/feed, "
                + "mount, shear, name. hold_ticks>0 for a modded entity needing continuous "
                + "right-click. Equip the needed item first.\n"
                + "hold_ticks: 0 = one press; >0 = hold that many ticks; -1 = hold until done "
                + "(dead / finished) or timeout. Use interact_at for blocks and thrown items.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("button", Map.of("type", "string", "enum", List.of("left", "right"),
                "description", "left = attack/hit, right = use/interact."));
        properties.put("entity_id", Map.of("type", "integer",
                "description", "Target entity id (from scan_nearby_entities)."));
        properties.put("hold_ticks", Map.of("type", List.of("integer", "null"),
                "description", "0/null = single press; >0 = hold that many ticks; -1 = hold until done/timeout (e.g. attack until dead)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("button", "entity_id", "hold_ticks"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        InteractEntityTaskRecord.Button button = readButton(args);
        if (!args.has("entity_id") || args.get("entity_id").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: entity_id");
        }
        int entityId;
        try {
            entityId = args.get("entity_id").getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("entity_id must be an integer: " + ex.getMessage());
        }
        int holdTicks = 0;
        if (args.has("hold_ticks") && !args.get("hold_ticks").isJsonNull()) {
            try {
                holdTicks = args.get("hold_ticks").getAsInt();
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("hold_ticks must be an integer or null: " + ex.getMessage());
            }
        }
        return new InteractEntityTaskRecord(toolCallId, currentGameTime + TIMEOUT_TICKS, button, entityId, holdTicks);
    }

    private static InteractEntityTaskRecord.Button readButton(JsonObject args) {
        if (!args.has("button") || args.get("button").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: button");
        }
        return switch (args.get("button").getAsString()) {
            case "left" -> InteractEntityTaskRecord.Button.LEFT;
            case "right" -> InteractEntityTaskRecord.Button.RIGHT;
            default -> throw new IllegalArgumentException(
                    "button must be 'left' or 'right', got: " + args.get("button").getAsString());
        };
    }
}
