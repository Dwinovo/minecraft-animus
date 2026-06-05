package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.PlaceBlockTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code place_block} tool — put a block from inventory at a target
 * position. The entity walks to a standable spot next to the target (bridging /
 * digging like move_to) and places it. You give an absolute coordinate; the
 * block must attach to an existing solid neighbour (no floating placements) and
 * the target cell must be empty/replaceable.
 *
 * <h2>Use cases</h2>
 * Torches for light (mob-proofing), walls/shelter, sealing caves, or putting a
 * crafting table / furnace / chest exactly where you want it.
 */
public final class PlaceBlockTool implements AnimusTool {

    private static final long MIN_TIMEOUT_TICKS = 30 * 20;   // covers walking to the spot

    @Override
    public String name() {
        return PlaceBlockTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Place a block from your inventory at an absolute coordinate. The "
                + "entity walks to a reachable spot next to the target and places it. "
                + "The block must attach to an existing solid neighbour — you can't "
                + "place a floating block in mid-air — and the target cell must be "
                + "empty. Fails with guidance if you don't carry the block, it isn't "
                + "a placeable block, the target is occupied, there's no support, or "
                + "no reachable spot to place from. Use it for torches (light), "
                + "walls/shelter, sealing caves, or positioning a crafting "
                + "table/furnace/chest.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("block_id", Map.of("type", "string",
                "description", "Namespaced id of the block item to place, e.g. minecraft:torch."));
        properties.put("x", Map.of("type", "integer", "description", "Target x."));
        properties.put("y", Map.of("type", "integer", "description", "Target y."));
        properties.put("z", Map.of("type", "integer", "description", "Target z."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("block_id", "x", "y", "z"));
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
        if (!(item instanceof BlockItem blockItem)) {
            throw new IllegalArgumentException(
                    BuiltInRegistries.ITEM.getKey(item) + " is not a placeable block");
        }
        BlockPos pos = new BlockPos(requireInt(args, "x"), requireInt(args, "y"), requireInt(args, "z"));
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new PlaceBlockTaskRecord(toolCallId, currentGameTime + MIN_TIMEOUT_TICKS,
                blockItem.getBlock(), item, pos, label);
    }

    private static Item readItem(JsonObject args) {
        if (!args.has("block_id") || args.get("block_id").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: block_id");
        }
        Identifier id = Identifier.tryParse(args.get("block_id").getAsString());
        if (id == null) {
            throw new IllegalArgumentException("block_id is not a valid id: " + args.get("block_id"));
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
