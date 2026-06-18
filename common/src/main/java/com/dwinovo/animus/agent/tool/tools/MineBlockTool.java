package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.MineBlockTaskRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code auto_mine} tool — intent-level gathering. The LLM declares
 * <em>what</em> block(s) to gather and <em>how many</em>; the entity does the
 * rest (find → pathfind, bridging/digging as needed → mine → repeat). No
 * coordinates, no manual move/scan composition.
 *
 * <h2>Schema</h2>
 * <pre>
 * {
 *   "block_ids": ["minecraft:iron_ore", "minecraft:deepslate_iron_ore"],
 *   "count": 10,
 *   "radius": 32          // optional, default auto-expands
 * }
 * </pre>
 *
 * <h2>Why a list of block_ids</h2>
 * A single logical resource often has several block forms (iron_ore +
 * deepslate_iron_ore; the log variants). Taking a list lets one call gather
 * "iron" regardless of which stratum it sits in — same convention as
 * {@code scan_blocks}.
 *
 * <h2>Partial success</h2>
 * If fewer than {@code count} exist within range the task still succeeds with
 * the real {@code mined} number reported, so the LLM can decide whether to
 * relocate or move on rather than blindly retrying.
 */
public final class MineBlockTool implements AnimusTool {

    private static final int DEFAULT_MAX_RADIUS = 48;
    private static final int MAX_ALLOWED_RADIUS = 96;
    private static final int MAX_COUNT = 256;
    /** Per-block budget is generous; total scales with count so big jobs don't time out. */
    private static final long TICKS_PER_BLOCK = 30 * 20;   // 30s each
    private static final long MIN_TIMEOUT_TICKS = 60 * 20; // 1 min floor

    @Override
    public String name() {
        return MineBlockTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Gather blocks by type and quantity. Give the block id(s) and how "
                + "many you want — the entity finds the nearest ones and travels "
                + "to each with full terrain-traversing navigation: it digs "
                + "tunnels to reach buried ores, pillars up to blocks high on "
                + "cliffs, and bridges gaps with cobblestone/dirt from its own "
                + "inventory, all automatically — then mines them and repeats "
                + "until it has gathered `count` of the resulting ITEMS or none "
                + "remain nearby. count is items, not blocks: a block can drop "
                + "several (redstone_ore → ~4 redstone), so count:10 redstone "
                + "mines only ~3 ore. It counts only NEW items gained, on top of "
                + "what you already carry. You do NOT "
                + "provide coordinates, call move_to, or pre-clear a path; "
                + "carrying some cobblestone/dirt helps it cross terrain. "
                + "Include all variants of a resource in block_ids "
                + "(e.g. iron_ore AND deepslate_iron_ore). Optional radius caps "
                + "how far to look (default auto-expands). Returns the actual "
                + "number gathered, which may be less than requested if the deposit "
                + "runs out. You must hold a tool that can harvest the target: "
                + "mining a block your main-hand tool can't harvest fails up front "
                + "and tells you the minimum tier required (e.g. iron_ore needs a "
                + "stone pickaxe). Equip the right pickaxe/axe/shovel yourself first "
                + "(equip_item) — check get_self_status for your main hand.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("block_ids", Map.of("type", "array",
                "description", "Namespaced block id(s) to gather; include all variants.",
                "items", Map.of("type", "string"),
                "minItems", 1));
        properties.put("count", Map.of("type", "integer",
                "description", "How many ITEMS to gather (not blocks) — a block may drop several, "
                        + "and it counts only items gained on top of what you already hold.",
                "minimum", 1, "maximum", MAX_COUNT));
        properties.put("radius", Map.of("type", "integer",
                "description", "Optional max search radius in blocks (default auto-expands to "
                        + DEFAULT_MAX_RADIUS + ").",
                "minimum", 1, "maximum", MAX_ALLOWED_RADIUS));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("block_ids", "count"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return MIN_TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        Set<Block> targets = readBlockIds(args);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("block_ids contained no valid block ids");
        }
        int count = requireInt(args, "count");
        if (count < 1) count = 1;
        if (count > MAX_COUNT) count = MAX_COUNT;

        int radius = DEFAULT_MAX_RADIUS;
        if (args.has("radius") && !args.get("radius").isJsonNull()) {
            radius = args.get("radius").getAsInt();
            if (radius < 1) radius = 1;
            if (radius > MAX_ALLOWED_RADIUS) radius = MAX_ALLOWED_RADIUS;
        }

        String label = labelFor(targets);
        long timeout = Math.max(MIN_TIMEOUT_TICKS, (long) count * TICKS_PER_BLOCK);
        long deadline = currentGameTime + timeout;
        return new MineBlockTaskRecord(toolCallId, deadline, targets, count, radius, label);
    }

    private static Set<Block> readBlockIds(JsonObject args) {
        if (!args.has("block_ids") || !args.get("block_ids").isJsonArray()) {
            throw new IllegalArgumentException("block_ids must be a non-empty array");
        }
        JsonArray arr = args.getAsJsonArray("block_ids");
        Set<Block> out = new LinkedHashSet<>();
        for (JsonElement el : arr) {
            if (el == null || el.isJsonNull()) continue;
            Identifier id = Identifier.tryParse(el.getAsString());
            if (id == null) continue;
            Block b = BuiltInRegistries.BLOCK.getValue(id);
            if (b != null && b != Blocks.AIR) out.add(b);
        }
        return out;
    }

    /** Short label for messages: the first target's path (e.g. "iron_ore"), "+N" if more. */
    private static String labelFor(Set<Block> targets) {
        Block first = targets.iterator().next();
        String path = BuiltInRegistries.BLOCK.getKey(first).getPath();
        return targets.size() == 1 ? path : path + "+" + (targets.size() - 1);
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
