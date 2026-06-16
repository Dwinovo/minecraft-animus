package com.dwinovo.animus.pathing.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * An immutable snapshot of the loaded chunks near a level's companions — the server-side equivalent of
 * Baritone's {@code createThreadSafeCopy()} of the chunk provider (docs/PATHFINDING_ASYNC.md). Built on
 * the main thread once per tick ({@link PathCaches#serverTick}) and read by the planner (off-thread
 * from P-C). It holds live {@link LevelChunk} references, so a lookup reads the LIVE section palette
 * (Baritone's {@code useTheRealWorld}) — exact for loaded terrain. We tolerate the rare race of reading
 * a palette the main thread is concurrently resizing (the reader catches it and yields AIR; the
 * executor re-costs live and replans), exactly the trade Baritone makes.
 *
 * <p>Never mutated after construction, so a worker reading the map structure can't race a writer — only
 * the shared chunk CONTENTS are live. A fresh snapshot is published (via {@link PathCaches}'s
 * {@link java.util.concurrent.ConcurrentHashMap}) each tick; an in-flight search keeps the snapshot it
 * started with.
 */
public final class LoadedChunks {

    private final Long2ObjectMap<LevelChunk> chunks;
    /** Packed positions ({@link BlockPos#asLong}) that held a block entity when this snapshot was taken
     *  — captured on the main thread so the don't-grief check is answerable off-thread without a live
     *  read (presence is all {@code shouldAvoidBreaking} needs). */
    private final LongSet blockEntities;

    LoadedChunks(Long2ObjectMap<LevelChunk> chunks, LongSet blockEntities) {
        this.chunks = chunks;
        this.blockEntities = blockEntities;
    }

    /** The loaded chunk at the given chunk coordinates, or {@code null} if it wasn't loaded when this
     *  snapshot was taken (→ the reader treats it as unknown / AIR). */
    public LevelChunk at(int chunkX, int chunkZ) {
        return chunks.get(ChunkPos.pack(chunkX, chunkZ));
    }

    /** Whether a block entity occupied {@code pos} when this snapshot was taken. Only meaningful for a
     *  position inside a captured chunk — {@link #blockEntities} is populated in lockstep with
     *  {@link #at}, and a cell outside the snapshot reads AIR (so the don't-grief check, which only
     *  runs on a breakable block, is never consulted there). */
    public boolean hasBlockEntity(BlockPos pos) {
        return blockEntities.contains(pos.asLong());
    }

    /** Number of chunks captured — for debug / memory accounting. */
    public int size() {
        return chunks.size();
    }
}
