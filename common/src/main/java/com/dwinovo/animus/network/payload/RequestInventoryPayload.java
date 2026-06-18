package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.platform.Services;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client → Server: "show me this companion's backpack." Other players' full
 * inventory isn't synced to clients (only equipment is), so the read-only Items
 * tab fetches it on demand. Answered with one {@link AnimusInventoryPayload}.
 *
 * <p>Only the owner of a LOADED companion gets the contents; otherwise the reply
 * is {@code loaded=false} (asleep / not yours — no inventory oracle).
 */
public record RequestInventoryPayload(UUID uuid) implements CustomPacketPayload {

    /** The 36 main backpack slots (hotbar + storage); equipment is already client-synced. */
    public static final int MAIN_SLOTS = 36;

    public static final Type<RequestInventoryPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "request_inventory"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestInventoryPayload> STREAM_CODEC =
            StreamCodec.composite(UUIDUtil.STREAM_CODEC, RequestInventoryPayload::uuid,
                    RequestInventoryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server main thread. */
    public static void handle(RequestInventoryPayload p, ServerPlayer player) {
        AnimusPlayer animus = AnimusPlayer.findByUuid(player.level().getServer(), p.uuid());
        if (animus == null || !animus.isOwnedByPlayer(player.getUUID())) {
            Services.NETWORK.sendToPlayer(player, new AnimusInventoryPayload(p.uuid(), false, List.of()));
            return;
        }
        Inventory inv = animus.getInventory();
        List<ItemStack> items = new ArrayList<>(MAIN_SLOTS);
        for (int i = 0; i < MAIN_SLOTS; i++) {
            items.add(inv.getItem(i).copy());
        }
        Services.NETWORK.sendToPlayer(player, new AnimusInventoryPayload(p.uuid(), true, items));
    }
}
