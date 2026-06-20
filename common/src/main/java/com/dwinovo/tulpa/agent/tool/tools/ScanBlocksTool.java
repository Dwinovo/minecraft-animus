package com.dwinovo.tulpa.agent.tool.tools;

import com.dwinovo.tulpa.agent.tool.TulpaTool;
import com.dwinovo.tulpa.entity.TulpaPlayer;
import com.dwinovo.tulpa.pathing.util.BlockScanner;
import com.dwinovo.tulpa.task.tasks.ScanBlocksJob;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
 * To actually <em>gather</em> blocks the LLM uses {@code auto_mine}, which
 * does its own find/pathfind/dig loop and needs no coordinates.
 *
 * <p>The heavy lifting (palette-short-circuited spherical search) lives in
 * {@link BlockScanner}, shared with the {@code auto_mine} goal.
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
public final class ScanBlocksTool implements TulpaTool {

    private static final int MIN_RADIUS = 1;
    /**
     * Chunk-scale reach (12 chunks out). Affordable because the scan is a
     * budget-sliced async job ({@link ScanBlocksJob}): palette-filtered
     * sections under a global per-tick cap, unloaded chunks paid for at the
     * shared 2-loads/tick pool — a big scan takes seconds of wall time and
     * zero tick stalls.
     */
    private static final int MAX_RADIUS = 192;
    private static final int MAX_RESULTS = 32;

    @Override
    public String name() { return "scan_blocks"; }

    @Override
    public String description() {
        return "Bulk find blocks of given type(s) within a spherical radius "
                + "around you. Returns matches sorted by distance, capped at "
                + MAX_RESULTS + ". A perception tool for surveying the area — to "
                + "actually gather blocks use auto_mine, which finds and digs "
                + "them itself. Radius is in blocks (max " + MAX_RADIUS + " = 12 "
                + "chunks — use big radii for landscape features like water, "
                + "lava lakes or villages' blocks; a big scan answers after a few "
                + "seconds, you can keep acting meanwhile). FLUIDS are scannable "
                + "too: minecraft:water / minecraft:lava work as block_ids, and "
                + "fluid matches carry source:true/false — only SOURCE lava turns "
                + "to obsidian under water, and only SOURCE water fills a bucket. "
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
    public boolean isAsyncQuery() { return true; }

    @Override
    public void startAsyncQuery(JsonObject args, TulpaPlayer entity,
                                java.util.function.Consumer<String> reply) {
        int radius = readInt(args, "radius", MIN_RADIUS, MAX_RADIUS);
        Set<Block> targets = readBlockIds(args);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("no valid block_ids provided");
        }
        if (!(entity.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            throw new IllegalArgumentException("not on a server level");
        }
        BlockPos center = entity.blockPosition();
        ScanBlocksJob.start(entity.getUUID(), sl, center, radius, targets,
                matches -> reply.accept(buildResult(matches, radius, center, null)),
                partial -> reply.accept(buildResult(partial.matches(), radius, center,
                        "partial: time budget hit after " + partial.columnsScanned() + "/"
                                + partial.columnsTotal() + " chunk columns — results cover "
                                + "the area nearest you; retry for fresh coverage or scan smaller")));
    }

    private static String buildResult(List<BlockScanner.Hit> matches, int radius,
                                      BlockPos center, String partialNote) {
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
            // Source vs flowing is THE decision bit for fluids: obsidian casting
            // and bucket-filling both demand a source cell.
            if (!s.state().getFluidState().isEmpty()) {
                o.addProperty("source", s.state().getFluidState().isSource());
            }
            out.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("matches", out);
        root.addProperty("total_found", matches.size());
        root.addProperty("truncated", matches.size() > MAX_RESULTS);
        root.addProperty("radius_searched", radius);
        if (partialNote != null) {
            root.addProperty("note", partialNote);
        }
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
            ResourceLocation id = ResourceLocation.tryParse(el.getAsString());
            if (id == null) continue;
            Block b = BuiltInRegistries.BLOCK.get(id);
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
