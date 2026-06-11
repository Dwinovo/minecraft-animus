package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.LoadFurnaceTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code load_furnace} tool — start an (asynchronous) smelt. The entity
 * finds a nearby furnace (or places one it carries), walks to it, and loads the
 * input + fuel into it, then returns immediately with the furnace's coordinates.
 * The world furnace smelts on its own afterwards; poll it with
 * {@code check_furnace} and empty it with {@code collect_furnace}.
 *
 * <h2>Why async</h2>
 * Smelting is slow (~10s/item). A real furnace keeps burning whether or not the
 * entity stands there, so we load-and-leave: the entity is free to go mine/fight
 * meanwhile and come back to collect. This is also why fuel must be supplied —
 * you pick the fuel type, the entity computes how much is needed for {@code count}.
 */
public final class LoadFurnaceTool implements AnimusTool {

    private static final int MAX_COUNT = 64;          // one furnace input slot
    private static final int FURNACE_SEARCH_RADIUS = 16;
    private static final long MIN_TIMEOUT_TICKS = 30 * 20;   // covers walking to / placing a furnace

    @Override
    public String name() {
        return LoadFurnaceTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Start smelting: the entity picks the nearest COMPATIBLE furnace "
                + "(busy ones — different item inside, slots full — are skipped "
                + "automatically; or it places a furnace it carries), walks to it, "
                + "and loads `count` of `input_id` plus `fuel_id` into it — then "
                + "returns immediately with the furnace coordinates. Pass optional "
                + "x/y/z to use ONE SPECIFIC furnace (e.g. to run several furnaces "
                + "in parallel on different items). Smelting then runs on its own "
                + "over real time (~10s per item); do other things and use "
                + "check_furnace to poll progress and collect_furnace to take the "
                + "output. You choose the fuel type (coal, charcoal, logs, planks, "
                + "…); the entity computes how much is needed. Fails with guidance "
                + "if the item isn't smeltable, the fuel is invalid, you lack "
                + "input/fuel, or every furnace is busy and none can be placed. "
                + "count is capped at 64 (one input slot).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("input_id", Map.of("type", "string",
                "description", "Namespaced id of the item to smelt, e.g. minecraft:raw_iron."));
        properties.put("count", Map.of("type", "integer",
                "description", "How many input items to load (1-64).",
                "minimum", 1, "maximum", MAX_COUNT));
        properties.put("fuel_id", Map.of("type", "string",
                "description", "Namespaced id of the fuel to use, e.g. minecraft:coal."));
        properties.put("x", Map.of("type", "integer",
                "description", "Optional: X of a specific furnace to use. Omit to auto-pick."));
        properties.put("y", Map.of("type", "integer",
                "description", "Optional: Y of a specific furnace to use."));
        properties.put("z", Map.of("type", "integer",
                "description", "Optional: Z of a specific furnace to use."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("input_id", "count", "fuel_id"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return MIN_TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        Item input = readItem(args, "input_id");
        Item fuel = readItem(args, "fuel_id");
        int count = requireInt(args, "count");
        if (count < 1) count = 1;
        if (count > MAX_COUNT) count = MAX_COUNT;

        String label = BuiltInRegistries.ITEM.getKey(input).getPath();
        long deadline = currentGameTime + MIN_TIMEOUT_TICKS;
        return new LoadFurnaceTaskRecord(toolCallId, deadline, input, count, fuel,
                FURNACE_SEARCH_RADIUS, label, readOptionalPos(args));
    }

    /** All three of x/y/z present → a specific furnace; none → auto-pick; mixed → error. */
    private static net.minecraft.core.BlockPos readOptionalPos(JsonObject args) {
        boolean hasX = args.has("x") && !args.get("x").isJsonNull();
        boolean hasY = args.has("y") && !args.get("y").isJsonNull();
        boolean hasZ = args.has("z") && !args.get("z").isJsonNull();
        if (!hasX && !hasY && !hasZ) return null;
        if (!(hasX && hasY && hasZ)) {
            throw new IllegalArgumentException("give all three of x/y/z to target a furnace, or none");
        }
        return new net.minecraft.core.BlockPos(
                requireInt(args, "x"), requireInt(args, "y"), requireInt(args, "z"));
    }

    private static Item readItem(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        Identifier id = Identifier.tryParse(args.get(key).getAsString());
        if (id == null) {
            throw new IllegalArgumentException(key + " is not a valid id: " + args.get(key));
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
