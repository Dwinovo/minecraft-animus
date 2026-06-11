package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.DepositItemsTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code deposit_items} tool — store inventory into a chest/barrel. The
 * civilized answer to a full inventory and the backbone of any base-storage
 * workflow; deposited containers are remembered in {@code <known_blocks>}.
 */
public final class DepositItemsTool implements AnimusTool {

    private static final int MAX_COUNT = 999;
    private static final int SEARCH_RADIUS = 16;
    private static final long TIMEOUT_TICKS = 30 * 20;

    @Override
    public String name() {
        return DepositItemsTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Store items from your inventory into a chest or barrel: the "
                + "entity walks to the nearest one (or to the specific container "
                + "at optional x/y/z — check <known_blocks> for ones you already "
                + "use) and moves `count` of `item_id` in. Use this when your "
                + "inventory fills up mid-task, or to organise loot at a base. "
                + "count above what you carry deposits everything you have of it. "
                + "Fails with guidance if no container is nearby (craft + "
                + "place_block a chest first) or the container is full. Returns "
                + "how many were stored, the container's coordinates, and what "
                + "remains on you.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return storageSchema(MAX_COUNT);
    }

    /** Shared schema for deposit_items / take_items (item, count, optional x/y/z). */
    static Map<String, Object> storageSchema(int maxCount) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("item_id", Map.of("type", "string",
                "description", "Namespaced id of the item, e.g. minecraft:iron_ingot."));
        properties.put("count", Map.of("type", "integer",
                "description", "How many (1-" + maxCount + ").",
                "minimum", 1, "maximum", maxCount));
        properties.put("x", Map.of("type", "integer",
                "description", "Optional: X of a specific chest/barrel. Omit to auto-pick the nearest."));
        properties.put("y", Map.of("type", "integer",
                "description", "Optional: Y of a specific chest/barrel."));
        properties.put("z", Map.of("type", "integer",
                "description", "Optional: Z of a specific chest/barrel."));

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
        Item item = DropItemsTool.ToolArgs.readItem(args, "item_id");
        int count = Math.clamp(DropItemsTool.ToolArgs.requireInt(args, "count"), 1, MAX_COUNT);
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new DepositItemsTaskRecord(toolCallId, currentGameTime + TIMEOUT_TICKS,
                item, count, label, DropItemsTool.ToolArgs.readOptionalPos(args), SEARCH_RADIUS);
    }
}
