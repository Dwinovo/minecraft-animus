package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ClientToolContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The {@code scan_blocks} tool — bulk find blocks of given type(s) in a
 * spherical region around the anchor entity. Returns matches sorted by
 * distance, capped at {@link #MAX_RESULTS}.
 *
 * <h2>Why this exists</h2>
 * {@code inspect_block(x,y,z)} is a single-point query. Asking the LLM to
 * "find iron ore" via repeated inspect_block calls is impractical (15k
 * iterations for radius 12). This tool gives the LLM Mineflayer-style
 * {@code findBlocks} as a single primitive.
 *
 * <h2>Performance — chunk-section palette short-circuit</h2>
 * Naive impl is {@code (2r+1)³} {@code getBlockState} calls — 15k for
 * radius 12. Instead, this iterates the chunks intersecting the bounding
 * box, and for each {@code LevelChunkSection} (16³ block subdivision)
 * calls {@link LevelChunkSection#maybeHas(Predicate)} which checks only
 * the section's palette (typically 2-10 entries) before deciding whether
 * to scan all 4096 inner blocks. Sections without the target block are
 * skipped instantly. Typical 50-200× speedup for sparse targets like
 * iron ore.
 *
 * <p>Mirrors Mineflayer's {@code bot.findBlocks} pattern
 * ({@code mineflayer/lib/plugins/blocks.js:156}) — section iteration with
 * palette prefilter.
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
                + MAX_RESULTS + ". Use this BEFORE pathfind_and_mine to discover "
                + "where the target blocks actually are — don't guess coordinates. "
                + "Radius is in blocks (max " + MAX_RADIUS + "). block_ids accepts "
                + "one or more namespaced ids; include all variants (e.g. both "
                + "iron_ore and deepslate_iron_ore for iron).";
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
        Predicate<BlockState> filter = state -> targets.contains(state.getBlock());

        BlockPos center = anchor.blockPosition();
        Level level = anchor.level();
        double radiusSq = (double) radius * radius;

        int minChunkX = SectionPos.blockToSectionCoord(center.getX() - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(center.getX() + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(center.getZ() + radius);
        int minSectionY = SectionPos.blockToSectionCoord(center.getY() - radius);
        int maxSectionY = SectionPos.blockToSectionCoord(center.getY() + radius);

        List<ScoredPos> matches = new ArrayList<>();

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ChunkAccess chunk = level.getChunk(cx, cz);
                if (chunk == null) continue;
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    int idx = level.getSectionIndexFromSectionY(sy);
                    if (idx < 0 || idx >= chunk.getSectionsCount()) continue;
                    LevelChunkSection section = chunk.getSection(idx);
                    if (section == null || section.hasOnlyAir()) continue;
                    // ✨ palette short-circuit: section's palette doesn't contain
                    // any target block → skip all 4096 inner blocks.
                    if (!section.maybeHas(filter)) continue;
                    scanSection(section, cx, sy, cz, center, radius, radiusSq, filter, matches);
                }
            }
        }

        matches.sort(Comparator.comparingDouble(s -> s.distance));
        return buildResult(matches, radius, center);
    }

    /** Iterate the 16³ blocks of one section, collect matches inside the sphere. */
    private static void scanSection(LevelChunkSection section,
                                     int chunkX, int sectionY, int chunkZ,
                                     BlockPos center, int radius, double radiusSq,
                                     Predicate<BlockState> filter,
                                     List<ScoredPos> out) {
        int baseX = SectionPos.sectionToBlockCoord(chunkX);
        int baseY = SectionPos.sectionToBlockCoord(sectionY);
        int baseZ = SectionPos.sectionToBlockCoord(chunkZ);
        for (int dx = 0; dx < 16; dx++) {
            int worldX = baseX + dx;
            int ddx = worldX - center.getX();
            if (ddx < -radius || ddx > radius) continue;
            for (int dy = 0; dy < 16; dy++) {
                int worldY = baseY + dy;
                int ddy = worldY - center.getY();
                if (ddy < -radius || ddy > radius) continue;
                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = baseZ + dz;
                    int ddz = worldZ - center.getZ();
                    if (ddz < -radius || ddz > radius) continue;
                    double distSq = (double) ddx * ddx + (double) ddy * ddy + (double) ddz * ddz;
                    if (distSq > radiusSq) continue;
                    BlockState state = section.getBlockState(dx, dy, dz);
                    if (!filter.test(state)) continue;
                    out.add(new ScoredPos(worldX, worldY, worldZ, state, Math.sqrt(distSq)));
                }
            }
        }
    }

    private static String buildResult(List<ScoredPos> matches, int radius, BlockPos center) {
        int limit = Math.min(matches.size(), MAX_RESULTS);
        JsonArray out = new JsonArray();
        for (int i = 0; i < limit; i++) {
            ScoredPos s = matches.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("x", s.x);
            o.addProperty("y", s.y);
            o.addProperty("z", s.z);
            o.addProperty("block", BuiltInRegistries.BLOCK.getKey(s.state.getBlock()).toString());
            o.addProperty("distance", s.distance);
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

    private record ScoredPos(int x, int y, int z, BlockState state, double distance) {}
}
