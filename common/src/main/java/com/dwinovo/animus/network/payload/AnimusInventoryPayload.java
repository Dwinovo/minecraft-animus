package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.client.data.ClientAnimusInventory;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Server → Client: a companion's 36 main backpack slots, answering
 * {@link RequestInventoryPayload}. {@code loaded=false} means the body is asleep
 * in unloaded chunks (or not the requester's) — no contents. Dropped into
 * {@link ClientAnimusInventory} for the Items tab to render read-only.
 */
public record AnimusInventoryPayload(UUID uuid, boolean loaded, List<ItemStack> items)
        implements CustomPacketPayload {

    public static final Type<AnimusInventoryPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "animus_inventory"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AnimusInventoryPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, AnimusInventoryPayload::uuid,
                    ByteBufCodecs.BOOL, AnimusInventoryPayload::loaded,
                    ItemStack.OPTIONAL_LIST_STREAM_CODEC, AnimusInventoryPayload::items,
                    AnimusInventoryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(AnimusInventoryPayload p) {
        ClientAnimusInventory.update(p.uuid(),
                new ClientAnimusInventory.Snapshot(p.loaded(), p.items(), System.currentTimeMillis()));
    }
}
