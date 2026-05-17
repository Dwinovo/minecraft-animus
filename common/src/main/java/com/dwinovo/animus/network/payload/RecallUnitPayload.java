package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.data.PlayerAnimusManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: discard the currently-spawned entity for unit_id.
 *
 * <p>Sent by two distinct code paths:
 * <ul>
 *   <li>{@code RecallUnitTool} — PlayerAgent explicitly aborts a unit's task.</li>
 *   <li>EntityAgent natural termination — when the EntityAgent emits a
 *       final-text reply (no tool_calls), the client tears down the loop
 *       and ships this payload so the server cleans up the in-world
 *       entity in lockstep.</li>
 * </ul>
 *
 * <p>Idempotent — no-op if the slot is already idle.
 */
public record RecallUnitPayload(int unitId) implements CustomPacketPayload {

    public static final Type<RecallUnitPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "recall_unit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RecallUnitPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RecallUnitPayload::unitId,
                    RecallUnitPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RecallUnitPayload p, ServerPlayer player) {
        PlayerAnimusManager.recallUnit(player, p.unitId());
        UnitsSnapshotPayload.sendTo(player);
        Constants.LOG.debug("[animus-net] recall_unit player={} unit={}",
                player.getName().getString(), p.unitId());
    }
}
