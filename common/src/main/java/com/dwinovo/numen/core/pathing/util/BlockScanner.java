package com.dwinovo.numen.core.pathing.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Shared spherical block search with a chunk-section palette short-circuit.
 * Factored out of the {@code scan_blocks} tool so the same efficient scan
 * backs both the LLM-facing perception tool and the internal
 * {@code MineBlockTaskGoal} (which repeatedly re-scans for the next nearest
 * target as it depletes a deposit).
 *
 * <h2>Performance</h2>
 * Naive search is {@code (2r+1)³} {@code getBlockState} calls (~15k for r=12).
 * Instead we iterate the chunk sections intersecting the bounding box and call
 * {@link LevelChunkSection#maybeHas(Predicate)} — which checks only the
 * section's palette (2-10 entries) before scanning its 4096 inner blocks.
 * Sections without any target block are skipped instantly (50-200× speedup for
 * sparse targets like ore). Mirrors Mineflayer's {@code bot.findBlocks}.
 */
public final class BlockScanner {

    /**
     * Hard cap on collected matches. Exists for landscape-scale targets
     * (water, lava: an ocean inside a 48-block radius is ~10⁵ matching cells)
     * — without it the match list explodes in memory before sorting. Scanning
     * stops once the cap is hit, so for super-abundant targets the result is
     * "plenty of nearby hits" rather than the guaranteed global nearest;
     * for sparse targets (ores, structures) the cap is never reached.
     */
    private static final int MAX_COLLECT = 8_192;

    private BlockScanner() {}

    /** One match: world position, its state, and Euclidean distance from the search centre. */
    public record Hit(BlockPos pos, BlockState state, double distance) {}

    /**
     * Find every block in {@code targets} within {@code radius} (spherical,
     * Euclidean) of {@code center}, sorted nearest-first.
     *
     * @param level   world to search
     * @param center  search origin (entity feet position)
     * @param radius  spherical radius in blocks
     * @param targets block types to match (include variants, e.g. iron_ore +
     *                deepslate_iron_ore)
     * @return matches sorted by ascending distance; empty if none
     */
    public static List<Hit> findWithin(Level level, BlockPos center, int radius, Set<Block> targets) {
        if (targets.isEmpty()) return List.of();
        Predicate<BlockState> filter = state -> targets.contains(state.getBlock());
        double radiusSq = (double) radius * radius;

        int minChunkX = SectionPos.blockToSectionCoord(center.getX() - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(center.getX() + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(center.getZ() + radius);
        int minSectionY = SectionPos.blockToSectionCoord(center.getY() - radius);
        int maxSectionY = SectionPos.blockToSectionCoord(center.getY() + radius);

        List<Hit> matches = new ArrayList<>();

        outer:
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                // LOADED chunks only (require=false): a synchronous scan must
                // never force chunk loads/generation — at radius 96 that used
                // to mean up to 49 sync loads inside one tick. Long-range
                // perception over unloaded terrain is ScanBlocksJob's job,
                // which loads under the global per-tick budget.
                ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    scanChunkSection(level, chunk, cx, sy, cz, center, radius, radiusSq, filter, matches);
                    if (matches.size() >= MAX_COLLECT) break outer;
                }
            }
        }

        matches.sort(Comparator.comparingDouble(Hit::distance));
        return matches;
    }

    /**
     * Main-thread: snapshot the LOADED chunks intersecting the search box, with
     * no loading/generation. The only world-API touch the async scan needs from
     * the main thread — cheap chunk-map lookups, not a block sweep. Hand the
     * result to {@link #scanLoaded} on a background thread.
     */
    public static List<ChunkAccess> captureLoadedChunks(Level level, BlockPos center, int radius) {
        int minChunkX = SectionPos.blockToSectionCoord(center.getX() - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(center.getX() + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(center.getZ() + radius);
        List<ChunkAccess> chunks = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk != null) chunks.add(chunk);
            }
        }
        return chunks;
    }

    /**
     * Off-thread: read the captured chunks' section palettes (Baritone's
     * {@code WorldScanner} does the same — palette reads of already-loaded
     * chunks). Reads can race with main-thread block writes, so each chunk is
     * guarded: a torn palette read skips that chunk this pass rather than
     * throwing. The result is advisory — callers re-validate every hit on the
     * main thread (prune / shaft) before mining.
     */
    public static List<Hit> scanLoaded(Level level, List<ChunkAccess> chunks,
                                       BlockPos center, int radius, Set<Block> targets) {
        if (targets.isEmpty() || chunks.isEmpty()) return List.of();
        Predicate<BlockState> filter = state -> targets.contains(state.getBlock());
        double radiusSq = (double) radius * radius;
        int minSectionY = SectionPos.blockToSectionCoord(center.getY() - radius);
        int maxSectionY = SectionPos.blockToSectionCoord(center.getY() + radius);

        List<Hit> matches = new ArrayList<>();
        outer:
        for (ChunkAccess chunk : chunks) {
            int cx = chunk.getPos().x;
            int cz = chunk.getPos().z;
            try {
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    scanChunkSection(level, chunk, cx, sy, cz, center, radius, radiusSq, filter, matches);
                    if (matches.size() >= MAX_COLLECT) break outer;
                }
            } catch (Throwable concurrentPaletteRead) {
                // The main thread mutated this chunk mid-read; skip it this pass.
            }
        }
        matches.sort(Comparator.comparingDouble(Hit::distance));
        return matches;
    }

    /**
     * Scan ONE section of an already-resolved chunk (palette short-circuit
     * included), appending sphere-clipped matches to {@code out}. Public so
     * the budget-sliced {@code ScanBlocksJob} can meter exactly this unit of
     * work per permit.
     */
    public static void scanChunkSection(Level level, ChunkAccess chunk,
                                        int chunkX, int sectionY, int chunkZ,
                                        BlockPos center, int radius, double radiusSq,
                                        Predicate<BlockState> filter,
                                        List<Hit> out) {
        int idx = level.getSectionIndexFromSectionY(sectionY);
        if (idx < 0 || idx >= chunk.getSectionsCount()) return;
        LevelChunkSection section = chunk.getSection(idx);
        if (section == null || section.hasOnlyAir()) return;
        // Palette short-circuit: skip all 4096 inner blocks when the
        // section's palette holds no target.
        if (!section.maybeHas(filter)) return;
        scanSection(section, chunkX, sectionY, chunkZ, center, radius, radiusSq, filter, out);
    }

    private static void scanSection(LevelChunkSection section,
                                    int chunkX, int sectionY, int chunkZ,
                                    BlockPos center, int radius, double radiusSq,
                                    Predicate<BlockState> filter,
                                    List<Hit> out) {
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
                    out.add(new Hit(new BlockPos(worldX, worldY, worldZ), state, Math.sqrt(distSq)));
                }
            }
        }
    }
}
