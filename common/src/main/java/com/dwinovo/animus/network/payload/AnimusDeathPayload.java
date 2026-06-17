package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Server → Client: an Animus body died for good. The owner's client-side
 * {@link com.dwinovo.animus.client.agent.EntityAgentLoop} must stop — a dead
 * companion has no body to act through, so it must never call the LLM again.
 *
 * <h2>Why a dedicated signal</h2>
 * Without it, a loop whose body just died keeps dispatching tools and gets back
 * misleading {@code entity not found} / {@code not loaded on client} errors,
 * which the LLM misreads as a transient sync glitch and retries until the loop
 * guard trips. This payload makes death explicit: the loop is hard-stopped and
 * disposed, so no further LLM turns happen.
 *
 * <h2>Death is recoverable (not disposed)</h2>
 * The companion respawns at its owner after a delay (see {@link AnimusRespawnPayload}), so the loop
 * is SUSPENDED, not disposed: {@code onEntityDied} resolves any in-flight tool calls with the death
 * cause (so the conversation stays valid and the brain learns WHY it stopped) and latches it idle.
 * {@code cause} is the vanilla death message ("X was slain by a zombie") for that tool result.
 */
public record AnimusDeathPayload(UUID entityUuid, String cause) implements CustomPacketPayload {

    public static final Type<AnimusDeathPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "animus_death"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AnimusDeathPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, AnimusDeathPayload::entityUuid,
                    ByteBufCodecs.STRING_UTF8, AnimusDeathPayload::cause,
                    AnimusDeathPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(AnimusDeathPayload p) {
        Constants.LOG.info("[animus-net] animus_death entity={} ({}) — suspending loop", p.entityUuid(), p.cause());
        AgentLoopRegistry.get(p.entityUuid()).ifPresent(loop -> loop.onEntityDied(p.cause()));
        // Not disposed: the body respawns at the owner and AnimusRespawnPayload resumes the loop.
        com.dwinovo.animus.client.agent.AnimusRoster.instance().remove(p.entityUuid());
    }
}
