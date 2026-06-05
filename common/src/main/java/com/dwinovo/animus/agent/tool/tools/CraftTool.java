package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.CraftTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code craft} tool — intent-level crafting. The LLM declares <em>what</em>
 * item to make and <em>how many</em>; the entity reverse-looks-up the recipe,
 * gathers the grid from its own inventory, walks to (or places) a crafting table
 * when the recipe needs the 3×3 grid, and crafts. No grid layout, no table
 * micro-management.
 *
 * <h2>Schema</h2>
 * <pre>
 * {
 *   "item_id": "minecraft:wooden_pickaxe",
 *   "count": 1
 * }
 * </pre>
 *
 * <h2>Voyager parity (no recursion)</h2>
 * Materials come only from the entity's own inventory. If an intermediate
 * product is missing (e.g. planks for a pickaxe) the call fails and the result
 * lists the shortfall ("missing 3x oak_planks") so the LLM can craft the
 * prerequisites first and retry — exactly like Voyager's {@code craftItem}.
 *
 * <h2>Count semantics</h2>
 * {@code count} is the number of output <em>items</em> wanted. Batch recipes
 * (planks → 4, sticks → 4) may overshoot on the final craft; the result reports
 * the real produced total.
 */
public final class CraftTool implements AnimusTool {

    private static final int MAX_COUNT = 256;
    private static final int TABLE_SEARCH_RADIUS = 16;
    private static final long MIN_TIMEOUT_TICKS = 30 * 20;   // 30s floor (covers walking to a table)
    private static final long TICKS_PER_ITEM = 20;           // generous; crafting itself is instant

    @Override
    public String name() {
        return CraftTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Craft an item by id and quantity. The entity finds the recipe, "
                + "gathers the ingredients from its OWN inventory, and crafts it — "
                + "walking to a nearby crafting table (or placing one it carries) "
                + "automatically for 3x3 recipes. A table it places is left in the "
                + "world (not picked back up) and its coordinates are reported, so "
                + "you can reuse it. You do NOT specify the grid or manage the "
                + "table. Materials are NOT auto-gathered from the world "
                + "and intermediate products are NOT auto-crafted: if something is "
                + "missing the call fails and reports the shortfall (e.g. \"missing "
                + "3x oak_planks\") so you can craft prerequisites first. count is "
                + "how many output items you want; batch recipes may yield a few "
                + "extra. Returns the actual number produced.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("item_id", Map.of("type", "string",
                "description", "Namespaced id of the item to craft, e.g. minecraft:wooden_pickaxe."));
        properties.put("count", Map.of("type", "integer",
                "description", "How many output items to produce.",
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
        return MIN_TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        Item item = readItem(args);
        int count = requireInt(args, "count");
        if (count < 1) count = 1;
        if (count > MAX_COUNT) count = MAX_COUNT;

        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        long timeout = Math.max(MIN_TIMEOUT_TICKS, (long) count * TICKS_PER_ITEM);
        long deadline = currentGameTime + timeout;
        return new CraftTaskRecord(toolCallId, deadline, item, count, TABLE_SEARCH_RADIUS, label);
    }

    private static Item readItem(JsonObject args) {
        if (!args.has("item_id") || args.get("item_id").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: item_id");
        }
        Identifier id = Identifier.tryParse(args.get("item_id").getAsString());
        if (id == null) {
            throw new IllegalArgumentException("item_id is not a valid id: " + args.get("item_id"));
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == null || item == Items.AIR) {
            throw new IllegalArgumentException("unknown item: " + id);
        }
        return item;
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
