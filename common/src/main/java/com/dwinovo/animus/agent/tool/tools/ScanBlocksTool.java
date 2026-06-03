package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ClientToolContext;
import com.dwinovo.animus.pathing.util.BlockScanner;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code scan_blocks} tool — bulk find blocks of given type(s) in a
 * spherical region around the anchor entity. Returns matches sorted by
 * distance, capped at {@link #MAX_RESULTS}.
 *
 * <h2>Why this exists</h2>
 * {@code inspect_block(x,y,z)} is a single-point query. Asking the LLM to
 * "find iron ore" via repeated inspect_block calls is impractical (15k
 * iterations for radius 12). This tool gives the LLM Mineflayer-style
 * {@code findBlocks} as a single primitive — a perception aid for surveying.
 * To actually <em>gather</em> blocks the LLM uses {@code mine_block}, which
 * does its own find/pathfind/dig loop and needs no coordinates.
 *
 * <p>The heavy lifting (palette-short-circuited spherical search) lives in
 * {@link BlockScanner}, shared with the {@code mine_block} goal.
 *
 * <h2>Schema</h2>
 * <pre>
 * {
 *   "radius": int [1, 12],
 *   "block_ids": ["minecraft:iron_ore", "minecraft:deepslate_iron_ore"]
 * }
 * </pre>
 * Radius is spherical (distance ≤ radius), not box — so radius 12 returns
 * blocks within 12 blocks Euclidean distance, not within a 25³ cube.
 */
public final class ScanBlocksTool implements AnimusTool {

    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 12;
    private static final int MAX_RESULTS = 32;

    @Override
    public String name() { return "scan_blocks"; }

    @Override
    public String description() {
        return "Bulk find blocks of given type(s) within a spherical radius "
                + "around you. Returns matches sorted by distance, capped at "
                + MAX_RESULTS + ". A perception tool for surveying the area — to "
                + "actually gather blocks use mine_block, which finds and digs "
                + "them itself. Radius is in blocks (max " + MAX_RADIUS + "). "
                + "block_ids accepts one or more namespaced ids; include all "
                + "variants (e.g. both iron_ore and deepslate_iron_ore for iron).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("radius", Map.of("type", "integer",
                "description", "Spherical search radius in blocks (max " + MAX_RADIUS + ").",
                "minimum", MIN_RADIUS, "maximum", MAX_RADIUS));
        properties.put("block_ids", Map.of("type", "array",
                "description", "List of namespaced block ids to search for.",
                "items", Map.of("type", "string"),
                "minItems", 1));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("radius", "block_ids"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() { return 1; }

    @Override
    public boolean isLocal() { return true; }

    @Override
    public String executeLocal(JsonObject args, ClientToolContext ctx) {
        LivingEntity anchor = ctx.anchor();
        if (anchor == null) {
            return "{\"success\":false,\"message\":\"perspective entity not available\"}";
        }

        int radius = readInt(args, "radius", MIN_RADIUS, MAX_RADIUS);
        Set<Block> targets = readBlockIds(args);
        if (targets.isEmpty()) {
            return "{\"success\":false,\"message\":\"no valid block_ids provided\"}";
        }

        BlockPos center = anchor.blockPosition();
        Level level = anchor.level();
        List<BlockScanner.Hit> matches = BlockScanner.findWithin(level, center, radius, targets);
        return buildResult(matches, radius, center);
    }

    private static String buildResult(List<BlockScanner.Hit> matches, int radius, BlockPos center) {
        int limit = Math.min(matches.size(), MAX_RESULTS);
        JsonArray out = new JsonArray();
        for (int i = 0; i < limit; i++) {
            BlockScanner.Hit s = matches.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("x", s.pos().getX());
            o.addProperty("y", s.pos().getY());
            o.addProperty("z", s.pos().getZ());
            o.addProperty("block", BuiltInRegistries.BLOCK.getKey(s.state().getBlock()).toString());
            o.addProperty("distance", s.distance());
            out.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("matches", out);
        root.addProperty("total_found", matches.size());
        root.addProperty("truncated", matches.size() > MAX_RESULTS);
        root.addProperty("radius_searched", radius);
        JsonObject centerJson = new JsonObject();
        centerJson.addProperty("x", center.getX());
        centerJson.addProperty("y", center.getY());
        centerJson.addProperty("z", center.getZ());
        root.add("center", centerJson);
        return root.toString();
    }

    private static Set<Block> readBlockIds(JsonObject args) {
        if (!args.has("block_ids") || !args.get("block_ids").isJsonArray()) {
            throw new IllegalArgumentException("block_ids must be a non-empty array");
        }
        JsonArray arr = args.getAsJsonArray("block_ids");
        Set<Block> out = new HashSet<>();
        for (JsonElement el : arr) {
            if (el == null || el.isJsonNull()) continue;
            Identifier id = Identifier.tryParse(el.getAsString());
            if (id == null) continue;
            Block b = BuiltInRegistries.BLOCK.getValue(id);
            if (b != null && b != Blocks.AIR) out.add(b);
        }
        return out;
    }

    private static int readInt(JsonObject args, String key, int min, int max) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        int v;
        try {
            v = args.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be an integer");
        }
        if (v < min) v = min;
        if (v > max) v = max;
        return v;
    }
}
