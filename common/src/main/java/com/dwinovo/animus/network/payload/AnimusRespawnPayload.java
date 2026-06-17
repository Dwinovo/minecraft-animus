package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Server → Client: a companion the owner saw die has respawned at the owner's side (the timed
 * recovery after {@link AnimusDeathPayload}). The suspended {@link com.dwinovo.animus.client.agent.EntityAgentLoop}
 * is reawakened — it picks up the conversation (which already carries the death tool result) with a
 * short "you respawned" note, so the brain continues coherently instead of starting blind.
 */
public record AnimusRespawnPayload(UUID entityUuid) implements CustomPacketPayload {

    public static final Type<AnimusRespawnPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "animus_respawn"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AnimusRespawnPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, AnimusRespawnPayload::entityUuid,
                    AnimusRespawnPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(AnimusRespawnPayload p) {
        Constants.LOG.info("[animus-net] animus_respawn entity={} — resuming loop", p.entityUuid());
        AgentLoopRegistry.get(p.entityUuid()).ifPresent(loop -> loop.onRespawned());
    }
}
