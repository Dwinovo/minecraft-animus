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
 * Server → Client: an asynchronous WORLD EVENT for a companion's brain (dimension change, a hazard,
 * …) — the generic version of Claude Code's "channel notification" (an external event pushed into a
 * running session). The server detects the event (edge-triggered) and ships a ready-made {@code <event>}
 * XML string; the client loop queues it like an owner prompt and splices it in at a protocol-valid
 * boundary. {@code urgent} wakes an idle brain to react now; otherwise it rides along on the next
 * owner-driven turn (no extra LLM call). Death is its own bespoke freeze/thaw pair, not this.
 */
public record AnimusEventPayload(UUID entityUuid, String xml, boolean urgent) implements CustomPacketPayload {

    public static final Type<AnimusEventPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "animus_event"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AnimusEventPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, AnimusEventPayload::entityUuid,
                    ByteBufCodecs.STRING_UTF8, AnimusEventPayload::xml,
                    ByteBufCodecs.BOOL, AnimusEventPayload::urgent,
                    AnimusEventPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(AnimusEventPayload p) {
        AgentLoopRegistry.get(p.entityUuid()).ifPresent(loop -> loop.injectEvent(p.xml(), p.urgent()));
    }
}
