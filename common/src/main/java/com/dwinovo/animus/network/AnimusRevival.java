package com.dwinovo.animus.network;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.entity.AnimusLastSeen;
import com.dwinovo.animus.network.payload.ExecuteToolPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Revives companions that sit in unloaded chunks when a tool call arrives.
 *
 * <p>A pet outside everyone's view keeps itself loaded with chunk tickets
 * only while engaged; after the linger window (or a server restart) it
 * unloads in place. {@code findByUuid} then misses — but the owner's agent
 * loop is very much alive and wants to talk to it. The cure: look up the
 * pet's last known whereabouts in {@link AnimusLastSeen}, drop the same
 * self-loading ticket there, and retry the dispatch for a few seconds while
 * the chunk (and the entity riding in it) loads. Entity attach is async, so
 * this is a poll, not a callback.
 *
 * <p>Server main thread only; ticked from both loaders' end-of-tick hooks.
 */
public final class AnimusRevival {

    /** Give chunk + entity loading this long before giving up (5s). */
    private static final int REVIVAL_TIMEOUT_TICKS = 100;
    /** Ticket radius for the revival poke — entity could have drifted a bit. */
    private static final int REVIVAL_TICKET_RADIUS = 2;

    private record Pending(ExecuteToolPayload payload, UUID playerUuid, long deadline,
                           AnimusLastSeen.Entry lastSeen) {}

    private static final List<Pending> PENDING = new ArrayList<>();

    private AnimusRevival() {}

    /**
     * Try to start a revival for a findByUuid miss. Returns {@code true} if
     * one was started (or already in flight) — the caller must NOT reply, the
     * retry will. Returns {@code false} when the pet is unknown to the index
     * (never seen / died) and the caller should fail the call normally.
     */
    public static boolean tryRevive(MinecraftServer server, ServerPlayer player, ExecuteToolPayload p) {
        for (Pending pending : PENDING) {
            if (pending.payload.toolCallId().equals(p.toolCallId())) return true;
        }
        AnimusLastSeen.Entry lastSeen = AnimusLastSeen.find(server, p.entityUuid());
        if (lastSeen == null) return false;
        ServerLevel level = server.getLevel(lastSeen.dimension());
        if (level == null) return false;
        level.getChunkSource().addTicketWithRadius(AnimusEntity.TASK_CHUNK_TICKET,
                ChunkPos.containing(lastSeen.pos()), REVIVAL_TICKET_RADIUS);
        PENDING.add(new Pending(p, player.getUUID(),
                server.getTickCount() + REVIVAL_TIMEOUT_TICKS, lastSeen));
        Constants.LOG.info("[animus-net] reviving entity {} at {} in {} for tool={} id={}",
                p.entityUuid(), lastSeen.pos().toShortString(),
                lastSeen.dimension().identifier(), p.toolName(), p.toolCallId());
        return true;
    }

    /** Poll pending revivals; re-dispatch on entity load, fail on deadline. */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerUuid());
            if (player == null) {            // owner logged off — nobody to answer
                it.remove();
                continue;
            }
            if (AnimusEntity.findByUuid(server, pending.payload.entityUuid()) != null) {
                it.remove();                 // remove FIRST: a re-miss must hard-fail, not requeue
                ExecuteToolPayload.handle(pending.payload, player);
                continue;
            }
            if (server.getTickCount() >= pending.deadline()) {
                it.remove();
                ExecuteToolPayload.replyError(player, pending.payload,
                        "companion did not load back in — last seen near "
                                + pending.lastSeen().pos().toShortString() + " in "
                                + pending.lastSeen().dimension().identifier()
                                + "; it may have died while away. Tell your owner those "
                                + "coordinates so they can check the site");
            }
        }
    }
}
