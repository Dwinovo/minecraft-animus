package com.dwinovo.tulpa.agent.tool.tools;

import com.dwinovo.tulpa.agent.tool.TulpaTool;
import com.dwinovo.tulpa.task.TaskRecord;
import com.dwinovo.tulpa.task.tasks.EatItemTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code eat_item} tool — eat a food from inventory to heal yourself. The
 * Tulpa eats it as a real timed action (chewing particles + sound over the
 * food's eat duration) and only heals + gains the food's effects once it
 * finishes; interrupting it mid-bite wastes nothing. Use it to self-heal in a
 * fight or on a long expedition. Healing scales with the food's nutrition;
 * special foods (e.g. golden apple) also grant their effects.
 *
 * <p>This is distinct from {@code use_item}: eating must affect the Tulpa, so it
 * does NOT go through the fake player.
 */
public final class EatItemTool implements TulpaTool {

    /** Generous — covers any food's eat duration (most ~1.6s) plus buffer. */
    private static final long TIMEOUT_TICKS = 15 * 20;

    @Override
    public String name() {
        return EatItemTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Eat or drink a consumable from your inventory. It's a real timed "
                + "action — chewing animation, particles and sound play over the eat "
                + "duration, and only when it finishes does it restore your hunger + "
                + "saturation and apply the item's effects (e.g. a golden apple's "
                + "regeneration/absorption). Your HP then regenerates naturally from "
                + "saturation, the same as a real player — so eat to refill hunger and "
                + "let health recover. Fails if you don't carry it, it isn't a "
                + "consumable, or your hunger is already full (the food is kept).";
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
        ResourceLocation id = ResourceLocation.tryParse(args.get("item_id").getAsString());
        if (id == null) {
            throw new IllegalArgumentException("item_id is not a valid id: " + args.get("item_id"));
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            throw new IllegalArgumentException("unknown item: " + id);
        }
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new EatItemTaskRecord(toolCallId, currentGameTime + TIMEOUT_TICKS, item, label);
    }
}
