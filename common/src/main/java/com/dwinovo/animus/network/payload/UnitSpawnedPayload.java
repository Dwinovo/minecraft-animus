package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → Client: result of a prior {@link SummonUnitPayload}. Carries
 * the vanilla {@code entity.getId()} on success or {@code -1} + a human
 * reason on failure.
 *
 * <p>Client-side handler wires up a new EntityAgent for the freshly-spawned
 * vanilla entity and dispatches the queued prompt for {@code unitId}.
 */
public record UnitSpawnedPayload(int unitId, int vanillaEntityId, String failReason)
        implements CustomPacketPayload {

    public static final int MAX_REASON_LENGTH = 512;

    public static final Type<UnitSpawnedPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "unit_spawned"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UnitSpawnedPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, UnitSpawnedPayload::unitId,
                    ByteBufCodecs.VAR_INT, UnitSpawnedPayload::vanillaEntityId,
                    ByteBufCodecs.stringUtf8(MAX_REASON_LENGTH), UnitSpawnedPayload::failReason,
                    UnitSpawnedPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Wired up to its client-side handler in {@code AnimusFabricClient} / {@code AnimusNeoForgeClient}. */
    public static void handle(UnitSpawnedPayload p) {
        // Defer to the client-side agent registry; implemented in the client package.
        com.dwinovo.animus.client.agent.AgentLoopRegistry.onUnitSpawned(p);
    }
}
