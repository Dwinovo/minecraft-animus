package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): eat/drink a consumable from the inventory. */
public final class EatItemTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final InventoryTools impl = new InventoryTools();

    private record Args(String item_id) {}

    @Override
    public String name() {
        return "eat_item";
    }

    @Override
    public String description() {
        return "Eat or drink a consumable from your inventory. It's a real timed action — chewing "
                + "animation, particles and sound play over the eat duration, and only when it finishes "
                + "does it restore your hunger + saturation and apply the item's effects (e.g. a golden "
                + "apple's regeneration/absorption). Your HP then regenerates naturally from saturation, "
                + "the same as a real player — so eat to refill hunger and let health recover. Fails if "
                + "you don't carry it, it isn't a consumable, or your hunger is already full (the food is kept).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the food to eat, e.g. minecraft:cooked_beef.")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.eatItem(a.item_id(), ctx(toolCallId, companion)));
    }
}
