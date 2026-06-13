package com.dwinovo.animus.network;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.network.payload.ExecuteToolPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The cold-start retry for a tool call that arrives a beat before its pet
 * finishes loading. The owner-liveness heartbeat ({@code KeepLoadedPayload})
 * normally has the pet loaded well before the first tool call, but a server
 * restart — or a synchronous query as the very first call after dormancy — can
 * race the async chunk load. When {@code findByUuid} misses, this drops a ticket
 * at the pet's resolved column (via {@link AnimusLocator}, the same resolver the
 * heartbeat uses) and re-dispatches the call once the entity loads, for a few
 * seconds before giving up. Entity attach is async, so this is a bounded poll,
 * not a callback.
 *
 * <p>Server main thread only; ticked from both loaders' end-of-tick hooks.
 */
public final class AnimusRevival {

    /** Give chunk + entity loading this long before giving up (5s). */
    private static final int REVIVAL_TIMEOUT_TICKS = 100;

    private record Pending(ExecuteToolPayload payload, UUID playerUuid, long deadline,
                           AnimusLocator.Target target) {}

    private static final List<Pending> PENDING = new ArrayList<>();

    private AnimusRevival() {}

    /**
     * Try to start a retry for a findByUuid miss. Returns {@code true} if one
     * was started (or already in flight) — the caller must NOT reply, the retry
     * will. Returns {@code false} when the pet is unknown / unowned / its level
     * is unloaded ({@link AnimusLocator} couldn't resolve it) and the caller
     * should fail the call normally.
     */
    public static boolean tryRevive(MinecraftServer server, ServerPlayer player, ExecuteToolPayload p) {
        for (Pending pending : PENDING) {
            if (pending.payload.toolCallId().equals(p.toolCallId())) return true;
        }
        AnimusLocator.Target target =
                AnimusLocator.resolveChunk(server, p.entityUuid(), player.getUUID());
        if (target == null) return false;
        target.level().getChunkSource().addTicketWithRadius(
                com.dwinovo.animus.init.InitTicketType.TASK, target.chunk(),
                com.dwinovo.animus.init.InitTicketType.TASK_TICKET_RADIUS);
        PENDING.add(new Pending(p, player.getUUID(),
                server.getTickCount() + REVIVAL_TIMEOUT_TICKS, target));
        Constants.LOG.info("[animus-net] reviving entity {} at {} in {} for tool={} id={}",
                p.entityUuid(), target.chunk(),
                target.level().dimension().identifier(), p.toolName(), p.toolCallId());
        return true;
    }

    /** Poll pending retries; re-dispatch on entity load, fail on deadline. */
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
                                + pending.target().chunk() + " in "
                                + pending.target().level().dimension().identifier()
                                + "; it may have died while away. Tell your owner those "
                                + "coordinates so they can check the site");
            }
        }
    }
}
