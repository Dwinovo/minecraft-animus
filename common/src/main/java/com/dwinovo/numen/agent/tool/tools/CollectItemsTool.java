package com.dwinovo.numen.agent.tool.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.tasks.CollectItemsTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code collect_items} tool — sweep dropped items off the ground. The
 * entity already auto-absorbs items it brushes against; this walks it to each
 * scattered drop so nothing is left behind after a mine or a hunt.
 *
 * <h2>Schema</h2>
 * <pre>
 * {
 *   "item_ids": ["minecraft:diamond"],   // optional; omit/empty = collect everything
 *   "radius": 16                          // optional
 * }
 * </pre>
 */
public final class CollectItemsTool implements NumenTool {

    private static final int DEFAULT_RADIUS = 16;
    private static final int MAX_RADIUS = 48;
    private static final long DEFAULT_TIMEOUT_TICKS = 60 * 20;   // 1 min

    @Override
    public String name() {
        return CollectItemsTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Pick up dropped items off the ground nearby. The entity travels to "
                + "each dropped item (it auto-absorbs items it gets close to) until "
                + "none remain in range — terrain is handled automatically: it digs "
                + "and bridges on its own if drops landed in a pit or across a gap. "
                + "Optionally restrict to specific item_ids "
                + "(omit to collect everything). Optional radius (default "
                + DEFAULT_RADIUS + "). Use after auto_mine or hunt to gather "
                + "scattered drops. Returns how many drops were collected.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("item_ids", Map.of("type", "array",
                "description", "Optional namespaced item id(s) to collect; omit to collect all.",
                "items", Map.of("type", "string")));
        properties.put("radius", Map.of("type", "integer",
                "description", "Optional search radius in blocks (default " + DEFAULT_RADIUS + ").",
                "minimum", 1, "maximum", MAX_RADIUS));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of());   // both optional — a bare call sweeps everything nearby
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return DEFAULT_TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        Set<Item> filter = ToolArgs.itemSet(args, "item_ids");

        int radius = DEFAULT_RADIUS;
        if (args.has("radius") && !args.get("radius").isJsonNull()) {
            radius = args.get("radius").getAsInt();
            if (radius < 1) radius = 1;
            if (radius > MAX_RADIUS) radius = MAX_RADIUS;
        }

        String label = filter.isEmpty() ? "all items" : labelFor(filter);
        long deadline = currentGameTime + DEFAULT_TIMEOUT_TICKS;
        return new CollectItemsTaskRecord(toolCallId, deadline, filter, radius, label);
    }

    private static String labelFor(Set<Item> filter) {
        Item first = filter.iterator().next();
        String path = BuiltInRegistries.ITEM.getKey(first).getPath();
        return filter.size() == 1 ? path : path + "+" + (filter.size() - 1);
    }
}
