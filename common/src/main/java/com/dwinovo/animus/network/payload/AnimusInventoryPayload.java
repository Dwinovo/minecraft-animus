package com.dwinovo.animus.network.payload;

import com.dwinovo.animus.Constants;
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
 * Server → Client: the full contents of one Animus entity's own inventory.
 * Pushed to the owner whenever the inventory changes (mining/pickup insert,
 * chest-menu drag) so the client-side {@code get_storage} tool stays in sync.
 *
 * <p>Keyed by the stable {@code entity.getUUID()}; the client stores it in
 * {@link com.dwinovo.animus.client.data.ClientAnimusInventories}.
 */
public record AnimusInventoryPayload(UUID entityUuid, List<ItemStack> contents)
        implements CustomPacketPayload {

    public static final Type<AnimusInventoryPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "animus_inventory"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AnimusInventoryPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, AnimusInventoryPayload::entityUuid,
                    ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()),
                    AnimusInventoryPayload::contents,
                    AnimusInventoryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Client-side handler. Runs on the client main thread. */
    public static void handle(AnimusInventoryPayload p) {
        com.dwinovo.animus.client.data.ClientAnimusInventories.put(
                p.entityUuid(), p.contents().toArray(new ItemStack[0]));
        Constants.LOG.debug("[animus-net] inventory snapshot entity={} ({} slots)",
                p.entityUuid(), p.contents().size());
    }
}
