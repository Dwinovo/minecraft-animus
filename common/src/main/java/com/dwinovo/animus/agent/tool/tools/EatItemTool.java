package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.EatItemTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code eat_item} tool — eat a food from inventory to heal yourself. The
 * Animus eats it as a real timed action (chewing particles + sound over the
 * food's eat duration) and only heals + gains the food's effects once it
 * finishes; interrupting it mid-bite wastes nothing. Use it to self-heal in a
 * fight or on a long expedition. Healing scales with the food's nutrition;
 * special foods (e.g. golden apple) also grant their effects.
 *
 * <p>This is distinct from {@code use_item}: eating must affect the Animus, so it
 * does NOT go through the fake player.
 */
public final class EatItemTool implements AnimusTool {

    /** Generous — covers any food's eat duration (most ~1.6s) plus buffer. */
    private static final long TIMEOUT_TICKS = 15 * 20;

    @Override
    public String name() {
        return EatItemTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Eat a food item from your inventory to heal yourself. It's a real "
                + "timed action — chewing particles and sound play over the food's "
                + "eat duration, and you only heal + gain the food's effects (e.g. a "
                + "golden apple's regeneration/absorption) once eating finishes. "
                + "Healing scales with the food's nutrition. Use it to recover in "
                + "combat or on long trips. Fails if you don't carry the item or it "
                + "isn't edible.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("item_id", Map.of("type", "string",
                "description", "Namespaced id of the food to eat, e.g. minecraft:cooked_beef."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("item_id"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
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
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new EatItemTaskRecord(toolCallId, currentGameTime + TIMEOUT_TICKS, item, label);
    }
}
