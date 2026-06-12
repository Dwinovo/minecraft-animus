package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.UseItemTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code use_item} tool — right-click an item the way a player would,
 * optionally on a target block. Runs through a real fake player so vanilla item
 * logic applies: flint&steel ignites (light a nether portal), an Eye of Ender
 * fills an end-portal frame (with coords) or is thrown (without), bonemeal
 * grows crops, buckets place/scoop fluid, etc.
 *
 * <h2>Schema</h2>
 * <pre>{ "item_id": "minecraft:flint_and_steel", "x": 10, "y": 64, "z": -3 }</pre>
 * Omit x/y/z to use the item in the air (throwables like an Eye of Ender to
 * locate a stronghold).
 *
 * <h2>Scope</h2>
 * World-affecting uses only. Self-effects (eating, drinking) are NOT this tool —
 * those would apply to the fake player, not the Animus.
 */
public final class UseItemTool implements AnimusTool {

    private static final long MIN_TIMEOUT_TICKS = 30 * 20;   // covers walking to a target block

    @Override
    public String name() {
        return UseItemTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Right-click an item like a player would, optionally on a target "
                + "block. Give x/y/z to use it ON that block (the entity walks "
                + "within reach first) — e.g. an ender_eye on an end_portal_frame "
                + "to fill it, bonemeal on a crop. To LIGHT A NETHER PORTAL with "
                + "flint_and_steel, point x/y/z at an EMPTY AIR cell INSIDE the "
                + "obsidian frame (not at the obsidian) — the fire lands in that "
                + "cell and the portal forms. BUCKETS work by targeting too: an "
                + "empty bucket on a SOURCE water/lava cell fills it; a full "
                + "bucket aimed at the cell to pour into empties it. Omit x/y/z "
                + "to use it in the air — "
                + "e.g. throw an ender_eye to locate a stronghold. Fails with "
                + "guidance if you don't hold the item, can't reach the target, or "
                + "the item does nothing there. NOTE: this is for world-affecting "
                + "uses; it is NOT for eating/drinking (that wouldn't heal you).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("item_id", Map.of("type", "string",
                "description", "Namespaced id of the item to use, e.g. minecraft:flint_and_steel."));
        properties.put("x", Map.of("type", "integer",
                "description", "Optional target block x (use ON a block). Omit for in-air use."));
        properties.put("y", Map.of("type", "integer", "description", "Optional target block y."));
        properties.put("z", Map.of("type", "integer", "description", "Optional target block z."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("item_id"));
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
        rejectBodyBoundItems(item);
        BlockPos target = null;
        boolean hasX = has(args, "x"), hasY = has(args, "y"), hasZ = has(args, "z");
        if (hasX || hasY || hasZ) {
            if (!(hasX && hasY && hasZ)) {
                throw new IllegalArgumentException("provide all of x, y, z to target a block, or none for in-air use");
            }
            target = new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
        }
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new UseItemTaskRecord(toolCallId, currentGameTime + MIN_TIMEOUT_TICKS, item, target, label);
    }

    private static boolean has(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull();
    }

    /**
     * The fake player is a WORLD actuator — anything whose effect lands on
     * the user's own body must not go through it. Consumables would feed the
     * fake player (no healing, item wasted); an ender pearl's landing
     * teleports its OWNER, and a borrowed connectionless fake player has no
     * defined answer to that. Was a javadoc convention; now it's code.
     */
    private static void rejectBodyBoundItems(Item item) {
        if (item.components().has(net.minecraft.core.component.DataComponents.CONSUMABLE)) {
            throw new IllegalArgumentException(
                    BuiltInRegistries.ITEM.getKey(item).getPath()
                            + " is a consumable — use eat_item instead; use_item acts on "
                            + "the WORLD, eating through it would not heal you");
        }
        if (item == Items.ENDER_PEARL) {
            throw new IllegalArgumentException(
                    "ender_pearl teleportation is body-bound and not supported — to "
                            + "travel, use move_to; to locate the stronghold, use "
                            + "locate_structure(\"minecraft:stronghold\")");
        }
    }

    private static Item readItem(JsonObject args) {
        if (!has(args, "item_id")) {
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
        return item;
    }
}
