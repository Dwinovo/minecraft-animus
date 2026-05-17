package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.data.PlayerAnimusData;
import com.dwinovo.animus.data.UnitConfig;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: the GUI changed a unit's configuration (display name
 * and/or model key). Server updates the persistent {@link UnitConfig} and,
 * if the unit is currently active in the world, pushes the new model_key
 * to the live entity so the visual updates immediately.
 *
 * <p>Empty string for {@code name} is interpreted as "clear name" (back to
 * the "Unit N" fallback display).
 */
public record UnitConfigUpdatePayload(int unitId, String name, String modelKey)
        implements CustomPacketPayload {

    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_MODEL_KEY_LENGTH = 256;

    public static final Type<UnitConfigUpdatePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "unit_config_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UnitConfigUpdatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, UnitConfigUpdatePayload::unitId,
                    ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), UnitConfigUpdatePayload::name,
                    ByteBufCodecs.stringUtf8(MAX_MODEL_KEY_LENGTH), UnitConfigUpdatePayload::modelKey,
                    UnitConfigUpdatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(UnitConfigUpdatePayload p, ServerPlayer player) {
        if (p.unitId() < 1 || p.unitId() > PlayerAnimusData.SLOT_COUNT) {
            Constants.LOG.warn("[animus-net] config-update bad unit_id={} from {}",
                    p.unitId(), player.getName().getString());
            return;
        }
        UnitConfig cfg = PlayerAnimusData.of(player).unit(p.unitId());
        String newName = p.name().isEmpty() ? null : p.name();
        cfg.setName(newName);
        if (!p.modelKey().isEmpty()) {
            cfg.setModelKey(p.modelKey());
            // If the unit is currently spawned, push the model change live.
            PlayerAnimusData data = PlayerAnimusData.of(player);
            if (data.isActive(p.unitId()) && player.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                var raw = sl.getEntity(data.activeVanillaId(p.unitId()));
                if (raw instanceof com.dwinovo.animus.entity.AnimusEntity ae) {
                    Identifier id = Identifier.tryParse(p.modelKey());
                    if (id != null) ae.setModelKey(id);
                }
            }
        }
        Constants.LOG.info("[animus-net] config-update player={} unit={} name={} model={}",
                player.getName().getString(), p.unitId(), newName, p.modelKey());
        UnitsSnapshotPayload.sendTo(player);
    }
}
