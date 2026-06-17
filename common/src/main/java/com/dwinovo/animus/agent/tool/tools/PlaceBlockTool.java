package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.PlaceBlockTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
                + "companion travels to a reachable spot next to the target on its own "
                + "— digging through obstacles, bridging gaps, pillaring up — then "
                + "places it like a real player (steps to the edge, looks, places). "
                + "The coordinate is the cell the block will OCCUPY, not the block it "
                + "sits on — to put a torch on top of a block at (x,y,z), target "
                + "(x,y+1,z). Placement still needs a block to attach to (you can't "
                + "place in pure mid-air), and the target cell must be empty. "
                + "Optional orientation for blocks that have one: `facing` "
                + "(north/south/east/west/up/down — furnace/chest/stairs/observer…), "
                + "`axis` (x/y/z — logs/pillars), `half` (top/bottom — slabs/stairs). "
                + "The result reports the block's ACTUAL orientation, so if it differs "
                + "from what you asked, break it and retry from another angle. Fails "
                + "with guidance (incl. nearby coords that WOULD work) if you lack the "
                + "block, it isn't placeable, the target is occupied, or there's no "
                + "reachable spot. Use for torches, walls/shelter, sealing caves, or "
                + "positioning a crafting table/furnace/chest.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("block_id", Map.of("type", "string",
                "description", "Namespaced id of the block item to place, e.g. minecraft:torch."));
        properties.put("x", Map.of("type", "integer", "description", "Target x."));
        properties.put("y", Map.of("type", "integer", "description", "Target y."));
        properties.put("z", Map.of("type", "integer", "description", "Target z."));
        properties.put("facing", Map.of("type", List.of("string", "null"),
                "enum", List.of("north", "south", "east", "west", "up", "down"),
                "description", "Optional. Which way the block should face (furnace/chest/stairs/…)."));
        properties.put("axis", Map.of("type", List.of("string", "null"),
                "enum", List.of("x", "y", "z"),
                "description", "Optional. Pillar/log axis (y = upright)."));
        properties.put("half", Map.of("type", List.of("string", "null"),
                "enum", List.of("top", "bottom"),
                "description", "Optional. Which half for a slab / stairs."));

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
        Direction facing = optEnum(args, "facing") == null ? null
                : Direction.byName(optEnum(args, "facing"));
        Direction.Axis axis = optEnum(args, "axis") == null ? null
                : Direction.Axis.byName(optEnum(args, "axis"));
        String half = optEnum(args, "half");
        Boolean topHalf = half == null ? null : half.equals("top");
        return new PlaceBlockTaskRecord(toolCallId, currentGameTime + MIN_TIMEOUT_TICKS,
                blockItem.getBlock(), item, pos, label, facing, axis, topHalf);
    }

    /** A lowercased optional enum string arg, or null if absent. */
    private static String optEnum(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) return null;
        String v = args.get(key).getAsString().trim().toLowerCase();
        return v.isEmpty() ? null : v;
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
