package com.dwinovo.tulpa.platform;

import com.dwinovo.tulpa.platform.services.INetworkChannel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Forge 1.20.4 implementation of {@link INetworkChannel}, built on a single
 * {@link SimpleChannel}. Each payload is registered as a message keyed by an
 * incrementing {@code int} discriminator; Forge maps an outbound payload back to
 * its registration by the message's runtime class, so {@code TulpaNetwork} can
 * keep sending plain payload instances via {@link #sendToServer} /
 * {@link #sendToPlayer}.
 *
 * <p>Unlike NeoForge there is no deferred "register during an event" window:
 * Forge accepts message registration immediately, so {@code register*} applies
 * straight to the channel (no pending queue / flush machinery).
 *
 * <h2>Threading</h2>
 * {@code consumerMainThread} hands the message to the consumer on the receiving
 * side's main thread (server-main for C→S, client-main for S→C), so common
 * handlers don't need to reschedule — matching the interface contract.
 */
public final class ForgeNetworkChannel implements INetworkChannel {

    private static final int PROTOCOL_VERSION = 1;

    private static final SimpleChannel CHANNEL = ChannelBuilder
            .named(new ResourceLocation("tulpa", "main"))
            .networkProtocolVersion(PROTOCOL_VERSION)
            .acceptedVersions((status, version) -> true)
            .simpleChannel();

    /** Monotonic discriminator for each registered message. */
    private int nextId = 0;

    /** Default constructor used by {@code ServiceLoader}. */
    public ForgeNetworkChannel() {}

    @Override
    public <T> void registerClientToServer(
            ResourceLocation id, Class<T> type,
            BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, ServerPlayer> handler) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread((msg, ctx) -> {
                    // getSender() is the ServerPlayer the C→S packet came from.
                    handler.accept(msg, ctx.getSender());
                    ctx.setPacketHandled(true);
                })
                .add();
    }

    @Override
    public <T> void registerServerToClient(
            ResourceLocation id, Class<T> type,
            BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder,
            Consumer<T> handler) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread((msg, ctx) -> {
                    // Runs only on the receiving (client) side, so the handler may
                    // safely touch client-only classes — they lazy-load here.
                    handler.accept(msg);
                    ctx.setPacketHandled(true);
                })
                .add();
    }

    @Override
    public void sendToServer(Object payload) {
        CHANNEL.send(payload, PacketDistributor.SERVER.noArg());
    }

    @Override
    public void sendToPlayer(ServerPlayer player, Object payload) {
        CHANNEL.send(payload, PacketDistributor.PLAYER.with(player));
    }
}
