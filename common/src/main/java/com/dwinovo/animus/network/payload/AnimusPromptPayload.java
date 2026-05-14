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
 * Client-to-server payload: a player typed a prompt into the Animus
 * interaction GUI and wants the entity's LLM agent to act on it.
 *
 * <h2>Server-side trust boundary</h2>
 * The client has zero authority — the server independently re-verifies:
 * <ul>
 *   <li>the target entity is an {@link AnimusEntity}</li>
 *   <li>the player is its owner ({@code TamableAnimal.isOwnedBy})</li>
 *   <li>the player is within {@link #MAX_INTERACT_DISTANCE_SQR}</li>
 * </ul>
 * Modified clients can send arbitrary {@code entityId} / text but won't get
 * past these checks. Failed validations log at debug and silently no-op
 * (matches {@code SetModelPayload}'s policy of not spamming the log on
 * malformed input).
 *
 * <h2>Text length cap</h2>
 * The client GUI enforces a soft limit but the server defends against
 * arbitrarily long prompts with {@link #MAX_PROMPT_LENGTH} — anything beyond
 * is truncated. Prevents a misbehaving client from blowing through tokens
 * by spamming long messages.
 */
public record AnimusPromptPayload(int entityId, String text) implements CustomPacketPayload {

    public static final int MAX_PROMPT_LENGTH = 1024;
    public static final double MAX_INTERACT_DISTANCE_SQR = 8.0 * 8.0;

    public static final Type<AnimusPromptPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "prompt"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AnimusPromptPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, AnimusPromptPayload::entityId,
                    ByteBufCodecs.stringUtf8(MAX_PROMPT_LENGTH), AnimusPromptPayload::text,
                    AnimusPromptPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on server main thread by the network channel. */
    public static void handle(AnimusPromptPayload payload, ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        Entity entity = serverLevel.getEntity(payload.entityId());
        if (!(entity instanceof AnimusEntity animus)) {
            Constants.LOG.debug("[animus-net] prompt rejected: entity {} is not an AnimusEntity",
                    payload.entityId());
            return;
        }
        if (!animus.isOwnedBy(player)) {
            Constants.LOG.debug("[animus-net] prompt rejected: player {} is not the owner",
                    player.getName().getString());
            return;
        }
        if (animus.distanceToSqr(player) > MAX_INTERACT_DISTANCE_SQR) {
            Constants.LOG.debug("[animus-net] prompt rejected: player {} too far from entity {}",
                    player.getName().getString(), payload.entityId());
            return;
        }
        String text = payload.text();
        if (text == null) text = "";
        text = text.trim();
        if (text.isEmpty()) return;
        if (text.length() > MAX_PROMPT_LENGTH) text = text.substring(0, MAX_PROMPT_LENGTH);
        animus.getAgentLoop().submitPrompt(text);
    }
}
