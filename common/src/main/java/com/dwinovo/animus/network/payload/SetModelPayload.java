package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Client-to-server payload requesting that a specific Animus entity render
 * with a different baked model. Validated server-side: only nearby Animus
 * entities can be retargeted by a given player.
 *
 * <h2>Wire format</h2>
 * Two fields, both length-prefixed varints / strings:
 * <ul>
 *   <li>{@code entityId} — vanilla entity id of the target Animus</li>
 *   <li>{@code modelKey} — the new model identifier
 *       ({@code animus:hachiware}, {@code animus_user:my_skin}, ...)</li>
 * </ul>
 *
 * <h2>Why server validates</h2>
 * The client GUI gates its own dispatches by distance, but the server can't
 * trust that gate — a modified client could send arbitrary entity ids /
 * model strings. The server-side {@link #handle} repeats the proximity check
 * and verifies the target is actually an {@link AnimusEntity}.
 */
public record SetModelPayload(int entityId, Identifier modelKey) implements CustomPacketPayload {

    /** Maximum block distance from player to target — matches the GUI's gate. */
    public static final double MAX_INTERACT_DISTANCE_SQR = 8.0 * 8.0;

    public static final Type<SetModelPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "set_model"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetModelPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SetModelPayload::entityId,
                    Identifier.STREAM_CODEC, SetModelPayload::modelKey,
                    SetModelPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Server-side handler invoked on the main server thread.
     * Runs proximity + type validation, then writes the synced model key.
     * Failures log at debug level and silently no-op — a misbehaving client
     * shouldn't spam the server log.
     */
    public static void handle(SetModelPayload payload, ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        Entity entity = serverLevel.getEntity(payload.entityId());
        if (!(entity instanceof AnimusEntity animus)) {
            Constants.LOG.debug("[animus-net] set_model rejected: entity {} is not an AnimusEntity",
                    payload.entityId());
            return;
        }
        if (animus.distanceToSqr(player) > MAX_INTERACT_DISTANCE_SQR) {
            Constants.LOG.debug("[animus-net] set_model rejected: player {} too far from entity {}",
                    player.getName().getString(), payload.entityId());
            return;
        }
        animus.setModelKey(payload.modelKey());
        Constants.LOG.debug("[animus-net] {} set entity {} model to {}",
                player.getName().getString(), payload.entityId(), payload.modelKey());
    }
}
