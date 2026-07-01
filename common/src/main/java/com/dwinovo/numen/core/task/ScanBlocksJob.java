package com.dwinovo.numen.core.task;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.core.pathing.util.BlockScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Budget-sliced long-range block scan — the async backend of the
 * {@code scan_blocks} tool for radii beyond what a single synchronous tick
 * can afford. Walks chunk COLUMNS on an expanding {@link RingSpiral} from the
 * scan center (nearest-first, so an early result cap still favours close
 * hits), scanning one palette-filtered 16³ section per
 * {@link SearchBudget#trySectionScan} permit and paying for not-yet-loaded
 * chunks with the same global {@link SearchBudget#tryChunkLoad} pool the
 * structure locator uses. Nothing here can stall a tick: every unit of work
 * is metered, and a deadline converts a too-expensive scan into an honest
 * partial result.
 *
 * <p>This is a QUERY in spirit — it never occupies the body or the task
 * queue; the pet keeps walking/mining while the scan runs. The reply rides
 * the normal async tool-result channel. Server main thread only; ticked from
 * both loaders' end-of-tick hooks.
 */
public final class ScanBlocksJob {

    /** Hard stop: convert a crawling scan into a partial answer (30s). */
    private static final int DEADLINE_TICKS = 600;
    /** Same collect cap as the synchronous scanner — bounds memory and sort. */
    private static final int MAX_COLLECT = 8_192;

    private static final List<ScanBlocksJob> JOBS = new ArrayList<>();

    private final UUID entityUuid;
    private final ResourceKey<Level> dimension;
    private final BlockPos center;
    private final int radius;
    private final double radiusSq;
    private final Predicate<BlockState> filter;
    private final Consumer<List<BlockScanner.Hit>> onDone;
    private final Consumer<ScanProgress> onPartial;

    private final int centerChunkX, centerChunkZ, maxRing, minSectionY, maxSectionY;
    private int ring, perimIdx;
    private long deadline = -1;
    private int columnsScanned;
    private final int columnsTotal;

    // Column in progress (budget ran dry mid-column); null = fetch next.
    private ChunkAccess currentChunk;
    private int currentChunkX, currentChunkZ, sectionCursor;

    private final List<BlockScanner.Hit> matches = new ArrayList<>();

    /** Completion summary for partial results. */
    public record ScanProgress(List<BlockScanner.Hit> matches, int columnsScanned, int columnsTotal) {}

    private ScanBlocksJob(UUID entityUuid, ServerLevel level, BlockPos center, int radius,
                          Set<Block> targets,
                          Consumer<List<BlockScanner.Hit>> onDone,
                          Consumer<ScanProgress> onPartial) {
        this.entityUuid = entityUuid;
        this.dimension = level.dimension();
        this.center = center;
        this.radius = radius;
        this.radiusSq = (double) radius * radius;
        this.filter = state -> targets.contains(state.getBlock());
        this.onDone = onDone;
        this.onPartial = onPartial;
        this.centerChunkX = SectionPos.blockToSectionCoord(center.getX());
        this.centerChunkZ = SectionPos.blockToSectionCoord(center.getZ());
        this.maxRing = Math.max(
                SectionPos.blockToSectionCoord(center.getX() + radius) - centerChunkX,
                centerChunkX - SectionPos.blockToSectionCoord(center.getX() - radius));
        this.minSectionY = SectionPos.blockToSectionCoord(
                Math.max(center.getY() - radius, level.getMinY()));
        this.maxSectionY = SectionPos.blockToSectionCoord(
                Math.min(center.getY() + radius, level.getMaxY()));
        int side = 2 * maxRing + 1;
        this.columnsTotal = side * side;
    }

    /** Register a scan; results arrive via the callbacks on a later tick. */
    public static void start(UUID entityUuid, ServerLevel level, BlockPos center, int radius,
                             Set<Block> targets,
                             Consumer<List<BlockScanner.Hit>> onDone,
                             Consumer<ScanProgress> onPartial) {
        ScanBlocksJob job = new ScanBlocksJob(entityUuid, level, center, radius, targets, onDone, onPartial);
        JOBS.add(job);
        Constants.LOG.info("[numen-scan] started radius-{} scan around {} in {} ({} columns)",
                radius, center.toShortString(), level.dimension().identifier(), job.columnsTotal);
    }

    /** Drop pending scans for one entity (owner interrupt — client already
     *  synthesized cancelled results, a late reply would be an orphan). */
    public static void cancelFor(UUID entityUuid) {
        JOBS.removeIf(job -> job.entityUuid.equals(entityUuid));
    }

    /** Advance all pending scans under the shared budget. */
    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) return;
        SearchBudget.refresh(server);
        Iterator<ScanBlocksJob> it = JOBS.iterator();
        while (it.hasNext()) {
            ScanBlocksJob job = it.next();
            if (job.tickOne(server)) it.remove();
        }
    }

    /** @return true when finished (reply sent). */
    private boolean tickOne(MinecraftServer server) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            finish(true);
            return true;
        }
        if (deadline < 0) deadline = server.getTickCount() + DEADLINE_TICKS;
        if (server.getTickCount() >= deadline) {
            finish(false);
            return true;
        }
        while (true) {
            if (currentChunk == null) {
                if (!nextColumn(level)) {
                    // Either exhausted (finish) or out of chunk-load budget (wait).
                    if (ring > maxRing) {
                        finish(true);
                        return true;
                    }
                    return false;
                }
            }
            // Scan the in-progress column one budgeted section at a time.
            while (sectionCursor <= maxSectionY) {
                if (!SearchBudget.trySectionScan()) return false;
                BlockScanner.scanChunkSection(level, currentChunk,
                        currentChunkX, sectionCursor, currentChunkZ,
                        center, radius, radiusSq, filter, matches);
                sectionCursor++;
                if (matches.size() >= MAX_COLLECT) {
                    // Ring order means what we have is the nearest area anyway.
                    finish(true);
                    return true;
                }
            }
            currentChunk = null;
            columnsScanned++;
        }
    }

    /**
     * Resolve the next spiral column into {@link #currentChunk}. Returns false
     * when the spiral is exhausted (ring > maxRing) OR the chunk-load budget
     * is dry (ring unchanged — retry next tick).
     */
    private boolean nextColumn(ServerLevel level) {
        while (ring <= maxRing) {
            if (perimIdx >= RingSpiral.perimeter(ring)) {
                ring++;
                perimIdx = 0;
                continue;
            }
            int[] d = RingSpiral.offset(ring, perimIdx);
            int cx = centerChunkX + d[0];
            int cz = centerChunkZ + d[1];
            ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) {
                // Not loaded: pay one global chunk-load permit, or wait.
                if (!SearchBudget.tryChunkLoad()) return false;
                chunk = level.getChunk(cx, cz);
            }
            perimIdx++;
            if (chunk == null) continue;   // failed to load — skip the column
            currentChunk = chunk;
            currentChunkX = cx;
            currentChunkZ = cz;
            sectionCursor = minSectionY;
            return true;
        }
        return false;
    }

    private void finish(boolean complete) {
        matches.sort(Comparator.comparingDouble(BlockScanner.Hit::distance));
        if (complete) {
            onDone.accept(matches);
        } else {
            onPartial.accept(new ScanProgress(matches, columnsScanned, columnsTotal));
        }
    }
}
