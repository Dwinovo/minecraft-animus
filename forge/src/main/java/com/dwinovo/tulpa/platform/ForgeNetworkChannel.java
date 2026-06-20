package com.dwinovo.tulpa.platform;

import com.dwinovo.tulpa.platform.services.INetworkChannel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Forge 1.20.1 implementation of {@link INetworkChannel}, built on a single classic
 * {@link SimpleChannel} (Forge 47.x predates the {@code ChannelBuilder} API). Each payload is
 * registered as a message keyed by an incrementing {@code int} discriminator and a fixed
 * {@link NetworkDirection}; Forge maps an outbound payload back to its registration by the
 * message's runtime class, so {@code TulpaNetwork} keeps sending plain payload instances.
 *
 * <h2>Threading</h2>
 * The classic API dispatches the handler on the network thread; we hop to the receiving side's
 * main thread with {@link NetworkEvent.Context#enqueueWork} (server-main for C→S, client-main for
 * S→C), matching the interface's threading contract.
 */
public final class ForgeNetworkChannel implements INetworkChannel {

    private static final String PROTOCOL_VERSION = "1";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("tulpa", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    /** Monotonic discriminator for each registered message. */
    private int nextId = 0;

    /** Default constructor used by {@code ServiceLoader}. */
    public ForgeNetworkChannel() {}

    @Override
    public <T> void registerClientToServer(
            ResourceLocation id, Class<T> type,
            BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, ServerPlayer> handler) {
        CHANNEL.registerMessage(nextId++, type, encoder, decoder,
                (msg, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ctx.enqueueWork(() -> handler.accept(msg, ctx.getSender()));   // server main thread
                    ctx.setPacketHandled(true);
                },
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    @Override
    public <T> void registerServerToClient(
            ResourceLocation id, Class<T> type,
            BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder,
            Consumer<T> handler) {
        CHANNEL.registerMessage(nextId++, type, encoder, decoder,
                (msg, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    // Runs only on the receiving (client) side, so the handler may safely touch
                    // client-only classes — they lazy-load inside the enqueued task.
                    ctx.enqueueWork(() -> handler.accept(msg));
                    ctx.setPacketHandled(true);
                },
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    @Override
    public void sendToServer(Object payload) {
        CHANNEL.sendToServer(payload);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, Object payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }
}
