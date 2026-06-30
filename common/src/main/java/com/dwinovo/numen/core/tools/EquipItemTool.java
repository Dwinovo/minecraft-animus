package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): equip an item (tool/weapon/armor/accessory) from the inventory. */
public final class EquipItemTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final InventoryTools impl = new InventoryTools();

    private record Args(String item_id, String slot) {}

    @Override
    public String name() {
        return "equip_item";
    }

    @Override
    public String description() {
        return "Equip an item from your OWN inventory so it actually takes effect — a tool/weapon to "
                + "the main hand (speeds up auto_mine, boosts melee), armor to its slot, a modded accessory "
                + "(Curios / Trinkets ring, amulet, …) to its accessory slot. It equips by right-clicking "
                + "the item the way a player does, so armor and accessories route to the correct slot on "
                + "their own. Omit slot for that auto-routing; set it only to force a hand or a specific "
                + "vanilla armor piece. Whatever was equipped before is stowed back. Fails if the item "
                + "isn't in your inventory.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item to equip; must be in the inventory.")
                .optionalEnum("slot", "Optional target slot; omit to auto-route by item type.",
                        "mainhand", "offhand", "head", "chest", "legs", "feet")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.equipItem(a.item_id(), a.slot(), ctx(toolCallId, companion)));
    }
}
