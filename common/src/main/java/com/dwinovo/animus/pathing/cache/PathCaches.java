package com.dwinovo.animus.pathing.cache;

import com.dwinovo.animus.entity.AnimusPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry + maintenance for the per-level {@link SectionCache}s (docs/PATHFINDING_ASYNC.md §5.1).
 * Every method here runs on the SERVER MAIN THREAD — they read live chunks; worker threads only read
 * the {@link SectionCache} they were handed. The loader event bridges (chunk-load, block-change,
 * end-of-tick) call into these; this class itself is loader-neutral (verified MC 26.1.2 APIs only).
 *
 * <p>A cache exists only for levels where a companion is actually operating ({@link #ensureWarm}
 * creates one); the block-change hook is a no-op for any other level, so an idle server pays nothing.
 */
public final class PathCaches {

    private PathCaches() {}

    private static final ConcurrentHashMap<ResourceKey<Level>, SectionCache> CACHES = new ConcurrentHashMap<>();

    /** The cache for {@code level}, creating an empty one if absent. */
    public static SectionCache of(ServerLevel level) {
        return CACHES.computeIfAbsent(level.dimension(), k -> new SectionCache());
    }

    /** The cache for {@code level}, or null — for the hot block-change path that must not create one. */
    public static SectionCache peek(Level level) {
        return CACHES.get(level.dimension());
    }

    /** Forget a level's cache (level unload / server stop). */
    public static void drop(ServerLevel level) {
        SectionCache c = CACHES.remove(level.dimension());
        if (c != null) {
            c.clear();
        }
    }

    public static void dropAll() {
        CACHES.clear();
    }

    // ---- per-tick maintenance (driven from both loaders' end-of-tick hook) ----

    /** Initial seed radius (chunks) when a level's first companion appears; the chunk-load hook keeps
     *  coverage as the companion travels, so this only needs to cover its immediate vicinity. */
    private static final int SEED_RADIUS_CHUNKS = 8;
    /** Prune cadence — pruning scans every cached section, so do it occasionally, not every tick. */
    private static final int PRUNE_INTERVAL_TICKS = 200;
    /** Keep a companion-less level's cache this long before freeing it, so a brief roster gap (a dormant
     *  re-summon, a one-tick teleport blip) doesn't drop and then fully re-seed ~hundreds of chunks. */
    private static final int CACHE_IDLE_GRACE_TICKS = 600;

    private static int tickCounter;
    /** Consecutive ticks each cached level has had no companion (main-thread only). */
    private static final Map<ResourceKey<Level>, Integer> idleTicks = new HashMap<>();

    /**
     * Once per server tick: group companions by level, seed a level's cache the first time a companion
     * is there, re-encode the sections that changed this tick, periodically prune far sections, and
     * free caches for levels that no longer host a companion (also handles dimension travel — the old
     * level's cache is dropped, the new one seeded). Cheap when no companions exist.
     */
    public static void serverTick(MinecraftServer server) {
        Map<ServerLevel, List<BlockPos>> byLevel = new HashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof AnimusPlayer && p.level() instanceof ServerLevel sl) {
                byLevel.computeIfAbsent(sl, k -> new ArrayList<>()).add(p.blockPosition());
            }
        }

        Set<ResourceKey<Level>> active = new HashSet<>();
        for (ServerLevel sl : byLevel.keySet()) {
            active.add(sl.dimension());
        }
        // Free caches for levels with no companion — but only after a grace period, so a transient
        // roster gap doesn't trigger a drop + full re-seed.
        for (ResourceKey<Level> key : new ArrayList<>(CACHES.keySet())) {
            if (active.contains(key)) {
                idleTicks.remove(key);
            } else if (idleTicks.merge(key, 1, Integer::sum) > CACHE_IDLE_GRACE_TICKS) {
                CACHES.remove(key);
                idleTicks.remove(key);
            }
        }

        boolean doPrune = (++tickCounter % PRUNE_INTERVAL_TICKS) == 0;
        for (Map.Entry<ServerLevel, List<BlockPos>> e : byLevel.entrySet()) {
            ServerLevel sl = e.getKey();
            List<BlockPos> feet = e.getValue();
            if (peek(sl) == null) {
                ensureWarm(sl, feet.get(0), SEED_RADIUS_CHUNKS);   // first companion → seed around it
            }
            flushDirty(sl);
            if (doPrune) {
                prune(sl, feet);
            }
        }
    }

    // ---- population / refresh (main thread) ----

    /** Encode every non-air section of a freshly loaded chunk — but only if this level already has a
     *  cache (a companion is operating here); otherwise there's nothing to keep warm. */
    public static void onChunkLoaded(ServerLevel level, LevelChunk chunk) {
        SectionCache cache = peek(level);
        if (cache != null) {
            encodeInto(cache, level, chunk);
        }
    }

    /**
     * Ensure {@code level} has a cache and seed it from the chunks already loaded within
     * {@code radiusChunks} of {@code center} — closes the gap of chunks that loaded before this
     * companion's cache existed (the chunk-load hook only catches loads from here on).
     */
    public static SectionCache ensureWarm(ServerLevel level, BlockPos center, int radiusChunks) {
        SectionCache cache = of(level);
        int ccx = SectionPos.blockToSectionCoord(center.getX());
        int ccz = SectionPos.blockToSectionCoord(center.getZ());
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(ccx + dx, ccz + dz);
                if (chunk != null) {
                    encodeInto(cache, level, chunk);
                }
            }
        }
        return cache;
    }

    /** Mark the section containing {@code pos} dirty (block-change hook). No-op if no cache here. */
    public static void onBlockChanged(Level level, BlockPos pos) {
        SectionCache cache = peek(level);
        if (cache != null) {
            cache.markDirty(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    /** Re-encode the sections that changed since the last flush (main thread, e.g. end of tick), so
     *  the off-thread copy tracks the live world. */
    public static void flushDirty(ServerLevel level) {
        SectionCache cache = peek(level);
        if (cache == null) {
            return;
        }
        for (long key : cache.drainDirty()) {
            int secX = SectionPos.x(key);
            int secY = SectionPos.y(key);
            int secZ = SectionPos.z(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(secX, secZ);
            if (chunk == null) {
                cache.remove(secX, secY, secZ);   // unloaded — drop, re-seed on next load
                continue;
            }
            int idx = level.getSectionIndexFromSectionY(secY);
            LevelChunkSection[] secs = chunk.getSections();
            if (idx < 0 || idx >= secs.length) {
                cache.remove(secX, secY, secZ);
                continue;
            }
            LevelChunkSection sec = secs[idx];
            if (sec == null || sec.hasOnlyAir()) {
                cache.remove(secX, secY, secZ);
            } else {
                cache.put(secX, secY, secZ, CompactSection.encode(sec));
            }
        }
    }

    /** Drop sections far from every companion (Baritone {@code CachedWorld.prune}, >1024 blocks). */
    public static void prune(ServerLevel level, Iterable<BlockPos> companionFeet) {
        SectionCache cache = peek(level);
        if (cache != null) {
            cache.prune(companionFeet);
        }
    }

    private static void encodeInto(SectionCache cache, ServerLevel level, LevelChunk chunk) {
        int cx = SectionPos.blockToSectionCoord(chunk.getPos().getMinBlockX());
        int cz = SectionPos.blockToSectionCoord(chunk.getPos().getMinBlockZ());
        LevelChunkSection[] secs = chunk.getSections();
        for (int i = 0; i < secs.length; i++) {
            LevelChunkSection sec = secs[i];
            if (sec == null || sec.hasOnlyAir()) {
                continue;
            }
            cache.put(cx, level.getSectionYFromSectionIndex(i), cz, CompactSection.encode(sec));
        }
    }
}
