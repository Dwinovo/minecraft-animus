package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → Client: the in-world entity backing a unit slot died unexpectedly
 * (took fatal damage, fell into the void, was killed by a mob). Client uses
 * this to dispose the corresponding entity agent loop and re-render the GUI
 * slot as dead.
 *
 * <p>Separate from {@code RecallUnitPayload} because that one is
 * client-initiated cleanup, whereas this one is server-initiated
 * notification.
 */
public record UnitDiedPayload(int unitId, String reason) implements CustomPacketPayload {

    public static final int MAX_REASON_LENGTH = 256;

    public static final Type<UnitDiedPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "unit_died"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UnitDiedPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, UnitDiedPayload::unitId,
                    ByteBufCodecs.stringUtf8(MAX_REASON_LENGTH), UnitDiedPayload::reason,
                    UnitDiedPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(UnitDiedPayload p) {
        com.dwinovo.animus.client.agent.AgentLoopRegistry.onUnitDied(p);
    }
}
