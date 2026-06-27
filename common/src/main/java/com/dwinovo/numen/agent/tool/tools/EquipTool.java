package com.dwinovo.numen.agent.tool.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.tasks.EquipTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code equip_item} tool — wear/wield an item from the entity's own
 * inventory. This is what makes {@code craft} pay off: a crafted pickaxe only
 * speeds up {@code auto_mine} once it's in the main hand, and crafted
 * armor/sword only help in combat once worn.
 *
 * <h2>Schema</h2>
 * <pre>
 * {
 *   "item_id": "minecraft:wooden_pickaxe",
 *   "slot": "mainhand"      // optional; omit to auto-route by item type
 * }
 * </pre>
 *
 * <p>Omit {@code slot} for the common case — armor routes to its armor slot,
 * tools/weapons to the main hand. Supply it only to force a hand slot or pick a
 * specific armor piece. Whatever was already in the target slot is stowed back
 * into the inventory.
 */
public final class EquipTool implements NumenTool {

    private static final long TIMEOUT_TICKS = 5 * 20;   // instant; generous floor

    private static final List<String> SLOT_NAMES =
            List.of("mainhand", "offhand", "head", "chest", "legs", "feet");

    @Override
    public String name() {
        return EquipTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Equip an item from your OWN inventory so it actually takes effect — "
                + "a tool/weapon to the main hand (speeds up auto_mine, boosts melee), "
                + "armor to its slot, a modded accessory (Curios / Trinkets ring, "
                + "amulet, …) to its accessory slot. It equips by right-clicking the "
                + "item the way a player does, so armor and accessories route to the "
                + "correct slot on their own. Omit slot for that auto-routing; set it "
                + "only to force a hand or a specific vanilla armor piece. Whatever was "
                + "equipped before is stowed back. Fails if the item isn't in your "
                + "inventory.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("item_id", Map.of("type", "string",
                "description", "Namespaced id of the item to equip; must be in the inventory."));
        properties.put("slot", Map.of("type", "string",
                "description", "Optional target slot; omit to auto-route by item type.",
                "enum", SLOT_NAMES));

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
        Item item = ToolArgs.requireItem(args, "item_id");
        EquipmentSlot slot = readSlot(args);

        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        long deadline = currentGameTime + TIMEOUT_TICKS;
        return new EquipTaskRecord(toolCallId, deadline, item, slot, label);
    }

    /** Parse the optional slot; {@code null} means auto-route. */
    private static EquipmentSlot readSlot(JsonObject args) {
        if (!args.has("slot") || args.get("slot").isJsonNull()) {
            return null;
        }
        String name = args.get("slot").getAsString().toLowerCase();
        return switch (name) {
            case "mainhand", "hand" -> EquipmentSlot.MAINHAND;
            case "offhand" -> EquipmentSlot.OFFHAND;
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            default -> throw new IllegalArgumentException("unknown slot: " + name);
        };
    }
}
