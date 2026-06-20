package com.dwinovo.tulpa.agent.tool.tools;

import com.dwinovo.tulpa.agent.tool.TulpaTool;
import com.dwinovo.tulpa.task.TaskRecord;
import com.dwinovo.tulpa.task.tasks.InteractAtTaskRecord;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code interact_at} — the point-aimed native interaction (the BLOCK + AIR columns of
 * vanilla's mouse input). Travel to the aim, look at it, press one mouse button on whatever
 * the crosshair raytrace resolves. Entities are handled by {@code interact_entity}.
 */
public final class InteractAtTool implements TulpaTool {

    private static final long TIMEOUT_TICKS = 30 * 20;   // covers walking to the aim

    @Override
    public String name() {
        return InteractAtTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Aim at a world point and press one mouse button — the native crosshair "
                + "interaction for BLOCKS and the AIR (moving entities use interact_entity). "
                + "Auto-paths within reach like move_to, then a raytrace resolves what's under the aim.\n"
                + "• button=right (use): activate the block at x,y,z (lever/button/door/bed/crafting "
                + "table/modded machine GUI), or USE an item ON it — e.g. bonemeal a crop, an "
                + "ender_eye on an end_portal_frame to fill it. LIGHT A NETHER PORTAL: flint_and_steel "
                + "aimed at an EMPTY AIR cell INSIDE the obsidian frame (not the obsidian). BUCKETS: "
                + "an empty bucket on a SOURCE water/lava cell fills it; a full bucket on the cell to "
                + "pour into empties it. Omit x,y,z (or aim at clear air) to use the held item in the "
                + "air — e.g. throw an ender_eye to locate a stronghold, lob a snowball.\n"
                + "• button=left (attack): break the block at x,y,z (native timed; the right tool must "
                + "be in inventory). Left-click on air does nothing.\n"
                + "item_id: optionally the item to use — it is equipped to the hand first, so you "
                + "don't need a separate equip_item. Omit to use whatever is already in hand.\n"
                + "hold_ticks: 0 = a single press; >0 = HOLD that many ticks (a modded machine that "
                + "needs continuous right-click, or a bow draw — 20 fully charges a bow); -1 = hold "
                + "until it finishes or the task times out. For routine breaking/placing prefer "
                + "break_block/place_block; eating/drinking is eat_item (not this).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("button", Map.of("type", "string", "enum", List.of("left", "right"),
                "description", "right = use/activate/throw, left = attack/break."));
        properties.put("x", Map.of("type", List.of("integer", "null"),
                "description", "Aim X. Null (with y,z null) = use the held item straight ahead (eat/drink)."));
        properties.put("y", Map.of("type", List.of("integer", "null"),
                "description", "Aim Y. Null when aiming forward."));
        properties.put("z", Map.of("type", List.of("integer", "null"),
                "description", "Aim Z. Null when aiming forward."));
        properties.put("hold_ticks", Map.of("type", List.of("integer", "null"),
                "description", "0/null = single press; >0 = hold that many ticks; -1 = hold until done/timeout."));
        properties.put("item_id", Map.of("type", List.of("string", "null"),
                "description", "Optional namespaced item to equip-and-use, e.g. minecraft:bonemeal. Null = use what's in hand."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("button", "x", "y", "z", "hold_ticks", "item_id"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        InteractAtTaskRecord.Button button = readButton(args);
        Integer x = optionalInt(args, "x");
        Integer y = optionalInt(args, "y");
        Integer z = optionalInt(args, "z");
        int holdTicks = optionalIntOr(args, "hold_ticks", 0);

        BlockPos aim = null;
        if (x != null || y != null || z != null) {
            if (x == null || y == null || z == null) {
                throw new IllegalArgumentException(
                        "an aim point needs all of x, y, z (or leave all null to use the held item straight ahead).");
            }
            aim = new BlockPos(x, y, z);
        }
        Item item = readItem(args);
        String bodyBound = InteractAtTaskRecord.bodyBoundReason(item);
        if (bodyBound != null) {
            throw new IllegalArgumentException(bodyBound);
        }
        return new InteractAtTaskRecord(toolCallId, currentGameTime + TIMEOUT_TICKS, button, aim, holdTicks, item);
    }

    /** Parse the optional item_id, or null when omitted. */
    private static Item readItem(JsonObject args) {
        if (!args.has("item_id") || args.get("item_id").isJsonNull()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(args.get("item_id").getAsString());
        if (id == null) {
            throw new IllegalArgumentException("item_id is not a valid id: " + args.get("item_id"));
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            throw new IllegalArgumentException("unknown item: " + id);
        }
        return item;
    }

    private static InteractAtTaskRecord.Button readButton(JsonObject args) {
        if (!args.has("button") || args.get("button").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: button");
        }
        return switch (args.get("button").getAsString()) {
            case "left" -> InteractAtTaskRecord.Button.LEFT;
            case "right" -> InteractAtTaskRecord.Button.RIGHT;
            default -> throw new IllegalArgumentException(
                    "button must be 'left' or 'right', got: " + args.get("button").getAsString());
        };
    }

    private static Integer optionalInt(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be an integer or null: " + ex.getMessage());
        }
    }

    private static int optionalIntOr(JsonObject args, String key, int fallback) {
        Integer v = optionalInt(args, key);
        return v != null ? v : fallback;
    }
}
