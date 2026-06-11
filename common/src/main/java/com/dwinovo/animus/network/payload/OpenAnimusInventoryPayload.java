package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ChestMenu;

/**
 * Client → Server: open a tamed Animus's own inventory as a vanilla 3×9 chest
 * menu. Triggered by the Inventory button in {@code EntityChatScreen}. The
 * server validates owner + proximity, then opens a standard {@link ChestMenu}
 * backed by the entity's {@link AnimusEntity#getInventory() container}; vanilla
 * handles the C↔S slot sync so the chest UI is fully draggable.
 */
public record OpenAnimusInventoryPayload(int entityId) implements CustomPacketPayload {

    /** Mirror of {@code ExecuteToolPayload}'s interaction range. */
    public static final double MAX_INTERACT_DISTANCE_SQR = 32.0 * 32.0;

    public static final Type<OpenAnimusInventoryPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "open_animus_inventory"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenAnimusInventoryPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, OpenAnimusInventoryPayload::entityId,
                    OpenAnimusInventoryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenAnimusInventoryPayload p, ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        Entity raw = level.getEntity(p.entityId());
        if (!(raw instanceof AnimusEntity animus)) return;
        if (!animus.isOwnedByPlayer(player.getUUID())) return;
        if (animus.distanceToSqr(player) > MAX_INTERACT_DISTANCE_SQR) return;

        player.openMenu(new SimpleMenuProvider(
                (id, inv, p2) -> ChestMenu.threeRows(id, inv, animus.getInventory()),
                Component.literal("Animus")));
        Constants.LOG.debug("[animus-net] open_animus_inventory entity={} for {}",
                p.entityId(), player.getName().getString());
    }
}
