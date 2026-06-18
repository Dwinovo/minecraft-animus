package com.dwinovo.tulpa.network.payload;

import com.dwinovo.tulpa.Constants;
import com.dwinovo.tulpa.client.agent.AgentLoopRegistry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Server → Client: a companion has respawned at the owner's side after dying — both the same-session
 * timed recovery and the at-login recovery for a companion that died while the owner was away. Carries
 * the {@code cause} so the brain always learns WHY it died, even when a logout wiped the client's
 * in-memory death state. The owner's {@link com.dwinovo.tulpa.client.agent.EntityAgentLoop} is created
 * if needed and reawakened with a death {@code <event>}.
 */
public record TulpaRespawnPayload(UUID entityUuid, String cause) implements CustomPacketPayload {

    public static final Type<TulpaRespawnPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "tulpa_respawn"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TulpaRespawnPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, TulpaRespawnPayload::entityUuid,
                    ByteBufCodecs.STRING_UTF8, TulpaRespawnPayload::cause,
                    TulpaRespawnPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that).
     *  getOrCreate (not get): after a logout the loop may not exist yet — make it so the death event lands. */
    public static void handle(TulpaRespawnPayload p) {
        Constants.LOG.info("[tulpa-net] tulpa_respawn entity={} ({}) — resuming loop", p.entityUuid(), p.cause());
        AgentLoopRegistry.getOrCreate(p.entityUuid()).onRespawned(p.cause());
    }
}
