package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.TakeItemsTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.Map;

/**
 * The {@code take_items} tool — withdraw from a chest/barrel into the
 * entity's inventory. The retrieval half of base storage: restock food,
 * arrows, scaffold blocks from what was deposited earlier.
 */
public final class TakeItemsTool implements AnimusTool {

    private static final int MAX_COUNT = 999;
    private static final int SEARCH_RADIUS = 16;
    private static final long TIMEOUT_TICKS = 30 * 20;

    @Override
    public String name() {
        return TakeItemsTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Take items out of a chest or barrel into your inventory: the "
                + "entity walks to the nearest one (or the specific container at "
                + "optional x/y/z — check <known_blocks>) and withdraws `count` "
                + "of `item_id`. Use it to restock food/arrows/blocks from base "
                + "storage. If the item isn't there, the failure lists what the "
                + "container actually holds. count above what's stored takes "
                + "everything available; stops cleanly if your inventory fills.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return DepositItemsTool.storageSchema(MAX_COUNT);
    }

    @Override
    public long defaultTimeoutTicks() {
        return TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        Item item = DropItemsTool.ToolArgs.readItem(args, "item_id");
        int count = Math.clamp(DropItemsTool.ToolArgs.requireInt(args, "count"), 1, MAX_COUNT);
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new TakeItemsTaskRecord(toolCallId, currentGameTime + TIMEOUT_TICKS,
                item, count, label, DropItemsTool.ToolArgs.readOptionalPos(args), SEARCH_RADIUS);
    }
}
