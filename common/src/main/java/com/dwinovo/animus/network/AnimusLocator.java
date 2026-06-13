package com.dwinovo.animus.network;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.entity.AnimusLastSeen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

/**
 * Resolves WHERE an owner's companion is, so a chunk ticket can be aimed at it
 * — the single source of truth shared by the owner-liveness heartbeat
 * ({@code KeepLoadedPayload}) and the tool-call cold-start retry
 * ({@link AnimusRevival}). An unloaded entity is invisible to {@code findByUuid},
 * so the answer comes from the live body when it's loaded and from the
 * persistent {@link AnimusLastSeen} index when it isn't.
 *
 * <p>Owner authorization lives here too: while a pet is unloaded it can't vouch
 * for its owner (vanilla ownership resolves through the pet's level), so the
 * index carries the owner UUID and every resolve is gated on it — without that,
 * anyone knowing a UUID could force-load foreign chunks.
 */
public final class AnimusLocator {

    private AnimusLocator() {}

    /** A chunk to force-load for a companion: the level it lives in and its column. */
    public record Target(ServerLevel level, ChunkPos chunk) {}

    /**
     * Where to force-load {@code entityUuid} for {@code ownerUuid}: the live
     * body's column if it's loaded, else its last-seen column. Returns
     * {@code null} when the pet is unknown to the server (never seen / dead),
     * its level is unloaded, or it is not owned by {@code ownerUuid}.
     */
    public static Target resolveChunk(MinecraftServer server, UUID entityUuid, UUID ownerUuid) {
        AnimusEntity live = AnimusEntity.findByUuid(server, entityUuid);
        if (live != null) {
            if (!live.isOwnedByPlayer(ownerUuid)) return null;
            if (!(live.level() instanceof ServerLevel sl)) return null;
            return new Target(sl, live.chunkPosition());
        }
        AnimusLastSeen.Entry seen = AnimusLastSeen.find(server, entityUuid);
        if (seen == null || !seen.owner().equals(ownerUuid)) return null;
        ServerLevel level = server.getLevel(seen.dimension());
        if (level == null) return null;
        return new Target(level, ChunkPos.containing(seen.pos()));
    }
}
