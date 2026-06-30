package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): pick up dropped items off the ground nearby. */
public final class CollectItemsTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final InventoryTools impl = new InventoryTools();

    private record Args(List<String> item_ids, Integer radius) {}

    @Override
    public String name() {
        return "collect_items";
    }

    @Override
    public String description() {
        return "Pick up dropped items off the ground nearby. The entity travels to each dropped item "
                + "(it auto-absorbs items it gets close to) until none remain in range — terrain is "
                + "handled automatically: it digs and bridges on its own if drops landed in a pit or "
                + "across a gap. Optionally restrict to specific item_ids (omit to collect everything). "
                + "Optional radius (default 16). Use after auto_mine or hunt to gather scattered drops. "
                + "Returns how many drops were collected.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalStringArray("item_ids", "Optional namespaced item id(s) to collect; omit to collect all.")
                .optionalInteger("radius", "Optional search radius in blocks (default 16).", 1, 48)
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.collectItems(a.item_ids(), a.radius(), ctx(toolCallId, companion)));
    }
}
