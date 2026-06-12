package com.dwinovo.animus.task.tasks;

import net.minecraft.server.MinecraftServer;

/**
 * GLOBAL per-tick budget for structure searching, shared by every
 * {@link LocateStructureTaskGoal} on the server (and any future sliced
 * search, e.g. a biome locator). The Explorer's Compass
 * {@code WorldWorkerManager} model: total search cost per tick is a server
 * constant, independent of how many companions are searching at once —
 * per-task budgets would stack linearly with pet count.
 *
 * <h2>Fairness</h2>
 * First-come-first-served within a tick (entities tick in a stable order), so
 * concurrent searches effectively serialize: the first finishes in a few
 * ticks, then the next drains the pool. For companion-scale concurrency
 * that's strictly better than splitting the pool — total latency is the same
 * and the implementation stays trivial. Revisit with round-robin only if
 * dozens of simultaneous searches ever become real.
 *
 * <h2>Threading</h2>
 * Server main thread only, like everything in the task layer. The tick stamp
 * uses {@link MinecraftServer#getTickCount()} (monotonic, unaffected by
 * {@code /tick freeze}) to reset the pool exactly once per server tick.
 */
final class StructureSearchBudget {

    /** Cached presence checks are cheap; this caps loop work across ALL searches. */
    private static final int MAX_CHECKS_PER_TICK = 128;
    /** STRUCTURE_STARTS chunk loads are the expensive fallback — strictly capped. */
    private static final int MAX_CHUNK_LOADS_PER_TICK = 2;
    /**
     * Biome locator samples (pure climate-noise lookups, no chunk access; one
     * "sample" = one x/z column across all its Y probes). Cheaper than a
     * structure check, hence the larger pool — still under the shared 4ms lid.
     */
    private static final int MAX_BIOME_SAMPLES_PER_TICK = 256;
    /**
     * Wall-clock hard stop. The count caps bound the common case; this makes
     * the "never stalls the server" promise unconditional even when every
     * check goes cold to disk. 4ms ≈ 8% of a 50ms tick.
     */
    private static final long MAX_NANOS_PER_TICK = 4_000_000L;

    private static int stampTick = Integer.MIN_VALUE;
    private static int checksLeft;
    private static int loadsLeft;
    private static int biomeSamplesLeft;
    private static long deadlineNanos;

    private StructureSearchBudget() {}

    /** Reset the pool when the server tick has advanced. Call before consuming. */
    static void refresh(MinecraftServer server) {
        int now = server.getTickCount();
        if (now != stampTick) {
            resetForTick(now);
        }
    }

    /** The actual pool reset; also the test seam (no MinecraftServer needed). */
    static void resetForTick(int tick) {
        stampTick = tick;
        checksLeft = MAX_CHECKS_PER_TICK;
        loadsLeft = MAX_CHUNK_LOADS_PER_TICK;
        biomeSamplesLeft = MAX_BIOME_SAMPLES_PER_TICK;
        deadlineNanos = System.nanoTime() + MAX_NANOS_PER_TICK;
    }

    /** Take one biome-sample permit; false = pool drained, resume next tick. */
    static boolean tryBiomeSample() {
        if (biomeSamplesLeft <= 0 || System.nanoTime() >= deadlineNanos) return false;
        biomeSamplesLeft--;
        return true;
    }

    /** Take one candidate-check permit; false = pool drained, resume next tick. */
    static boolean tryCheck() {
        if (checksLeft <= 0 || System.nanoTime() >= deadlineNanos) return false;
        checksLeft--;
        return true;
    }

    /** Take one chunk-load permit (the expensive op); false = resume next tick. */
    static boolean tryChunkLoad() {
        if (loadsLeft <= 0 || System.nanoTime() >= deadlineNanos) return false;
        loadsLeft--;
        return true;
    }
}
