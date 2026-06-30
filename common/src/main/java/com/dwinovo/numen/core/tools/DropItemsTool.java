package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): drop items onto the ground in front of the body. */
public final class DropItemsTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final InventoryTools impl = new InventoryTools();

    private record Args(String item_id, int count) {}

    @Override
    public String name() {
        return "drop_items";
    }

    @Override
    public String description() {
        return "Drop items from your inventory onto the ground in front of you — to hand something to "
                + "your owner, or to shed junk when your inventory is full and no chest is nearby (prefer "
                + "deposit_items when one is — dropped items despawn after 5 minutes). count above what "
                + "you carry drops everything you have of it. Returns how many were dropped and how many remain.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item to drop, e.g. minecraft:cobblestone.")
                .integer("count", "How many to drop (1-999).", 1, 999)
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.dropItems(a.item_id(), a.count(), ctx(toolCallId, companion)));
    }
}
