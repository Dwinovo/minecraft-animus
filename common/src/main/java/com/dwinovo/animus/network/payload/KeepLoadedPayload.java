package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.init.InitTicketType;
import com.dwinovo.animus.network.AnimusLocator;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client → Server heartbeat: "my agent loop for this companion is alive — keep
 * it loaded." Sent on a cadence by {@code ClientHeartbeat} while the owner's
 * {@code EntityAgentLoop} is mid-turn (thinking, awaiting tool results, or with
 * a queued prompt). This is the owner-liveness LEASE that replaced the old
 * "guess two minutes of linger after the last tool call" — the client, which
 * is the only side that knows the loop is still working, simply says so.
 *
 * <p>When the heartbeats stop (the loop goes idle or the owner disconnects),
 * nothing refreshes the ticket and it expires within
 * {@link InitTicketType#TASK_TIMEOUT_TICKS}; the pet goes dormant in place, its
 * whereabouts retained in the last-seen index. The next heartbeat (or a tool
 * call) reloads it via {@link AnimusLocator}.
 */
public record KeepLoadedPayload(UUID entityUuid) implements CustomPacketPayload {

    public static final Type<KeepLoadedPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "keep_loaded"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KeepLoadedPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, KeepLoadedPayload::entityUuid,
                    KeepLoadedPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler on the server main thread: refresh the lease ticket at the pet's column. */
    public static void handle(KeepLoadedPayload p, ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        AnimusLocator.Target target =
                AnimusLocator.resolveChunk(server, p.entityUuid(), player.getUUID());
        if (target == null) return;   // unknown / not owned — a heartbeat, so just ignore
        target.level().getChunkSource().addTicketWithRadius(
                InitTicketType.TASK, target.chunk(), InitTicketType.TASK_TICKET_RADIUS);
        // Stamp liveness on the body when it's loaded — dimension-follow reads
        // this to leave a conversing-but-idle pet behind (isOwnerLoopLive).
        AnimusEntity live = AnimusEntity.findByUuid(server, p.entityUuid());
        if (live != null) live.markOwnerHeartbeat();
    }
}
