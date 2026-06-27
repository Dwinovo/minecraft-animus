package com.dwinovo.numen.agent.tool.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.tasks.DropItemsTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code drop_items} tool — discard inventory onto the ground. The blunt
 * half of inventory management: hand things to the owner at their feet, or
 * shed junk (cobblestone, dirt) when the bags are full and no chest is near.
 */
public final class DropItemsTool implements NumenTool {

    private static final int MAX_COUNT = 999;
    private static final long TIMEOUT_TICKS = 10 * 20;

    @Override
    public String name() {
        return DropItemsTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Drop items from your inventory onto the ground in front of you — "
                + "to hand something to your owner, or to shed junk when your "
                + "inventory is full and no chest is nearby (prefer deposit_items "
                + "when one is — dropped items despawn after 5 minutes). count "
                + "above what you carry drops everything you have of it. Returns "
                + "how many were dropped and how many remain.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("item_id", Map.of("type", "string",
                "description", "Namespaced id of the item to drop, e.g. minecraft:cobblestone."));
        properties.put("count", Map.of("type", "integer",
                "description", "How many to drop (1-" + MAX_COUNT + ").",
                "minimum", 1, "maximum", MAX_COUNT));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("item_id", "count"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        Item item = ToolArgs.requireItem(args, "item_id");
        int count = Math.clamp(ToolArgs.requireInt(args, "count"), 1, MAX_COUNT);
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new DropItemsTaskRecord(toolCallId, currentGameTime + TIMEOUT_TICKS,
                item, count, label);
    }
}
