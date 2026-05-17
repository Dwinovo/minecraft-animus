package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.data.PlayerAnimusManager;
import com.dwinovo.animus.data.PlayerAnimusManager.SummonResult;
import com.dwinovo.animus.platform.Services;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: "PlayerAgent wants to assign a task to unit {@code unitId},
 * please summon it." The prompt itself is held client-side and dispatched
 * once {@link UnitSpawnedPayload} comes back — keeps the wire small and
 * avoids round-tripping a potentially large prompt blob.
 *
 * <h2>Trust model</h2>
 * Player-scoped only — the unit slot is keyed by sender. There is no way
 * for one player to summon another's units.
 *
 * <h2>Failure handling</h2>
 * On summon failure (busy, dead, no safe spawn, ...) the server still
 * sends back a {@link UnitSpawnedPayload} with {@code vanillaEntityId = -1}
 * and the failure reason; the client-side AssignTaskTool then synthesises
 * a fail tool_result so the LLM's ReAct loop continues.
 */
public record SummonUnitPayload(int unitId) implements CustomPacketPayload {

    public static final Type<SummonUnitPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "summon_unit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SummonUnitPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SummonUnitPayload::unitId,
                    SummonUnitPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SummonUnitPayload p, ServerPlayer player) {
        SummonResult result = PlayerAnimusManager.summonUnit(player, p.unitId());
        if (result instanceof SummonResult.Ok ok) {
            Services.NETWORK.sendToPlayer(player,
                    new UnitSpawnedPayload(p.unitId(), ok.entity().getId(), ""));
            // Push fresh snapshot so the client mirror sees the new active flag.
            UnitsSnapshotPayload.sendTo(player);
            Constants.LOG.debug("[animus-net] summon ok player={} unit={} vanilla={}",
                    player.getName().getString(), p.unitId(), ok.entity().getId());
        } else if (result instanceof SummonResult.Fail fail) {
            Services.NETWORK.sendToPlayer(player,
                    new UnitSpawnedPayload(p.unitId(), -1, fail.reason()));
            Constants.LOG.warn("[animus-net] summon failed player={} unit={} reason={}",
                    player.getName().getString(), p.unitId(), fail.reason());
        }
    }
}
