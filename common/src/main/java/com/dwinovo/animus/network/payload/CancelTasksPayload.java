package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Client-to-server payload: "the owner pressed Stop — cancel everything my
 * entity is doing". Sent by {@code EntityAgentLoop.abort()} alongside the
 * client-side turn teardown, so the interrupt stops the <em>body</em> (the
 * running task: walking, mining, placing), not just the model conversation.
 *
 * <h2>Trust model</h2>
 * Owner check only — no distance check, deliberately: the owner must be able
 * to stop a companion that has wandered far away. Cancelling is the one action
 * that is always safe to allow from anywhere.
 *
 * <p>The server-side {@code CANCELLED} results this produces are shipped back
 * via {@link TaskResultPayload} as usual; the client agent loop has already
 * synthesized "interrupted by owner" results for those tool-call ids and drops
 * the real ones as late arrivals. This payload's job is purely the body stop.
 */
public record CancelTasksPayload(UUID entityUuid) implements CustomPacketPayload {

    public static final Type<CancelTasksPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "cancel_tasks"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CancelTasksPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, CancelTasksPayload::entityUuid,
                    CancelTasksPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the server main thread. */
    public static void handle(CancelTasksPayload p, ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        Entity raw = level.getEntity(p.entityUuid());
        if (!(raw instanceof AnimusEntity animus)) {
            Constants.LOG.debug("[animus-net] cancel_tasks for unknown entity {}", p.entityUuid());
            return;
        }
        if (!animus.isOwnedBy(player)) {
            Constants.LOG.warn("[animus-net] ✗ cancel_tasks rejected from {}: not the owner",
                    player.getName().getString());
            return;
        }
        animus.cancelAllTasks("interrupted by owner");
        Constants.LOG.info("[animus-net] ✓ cancel_tasks on entity {} for {}",
                p.entityUuid(), player.getName().getString());
    }
}
