package com.dwinovo.animus.platform;

import com.dwinovo.animus.platform.services.INetworkChannel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * NeoForge implementation of {@link INetworkChannel}. NeoForge requires
 * payload registration to happen during the {@code RegisterPayloadHandlersEvent}
 * window, so this channel buffers registrations (both C→S and S→C) into a
 * pending queue and flushes them when
 * {@link #flushPending(RegisterPayloadHandlersEvent)} is invoked from the
 * event handler (see {@code AnimusMod}).
 *
 * <p>{@code IPayloadContext} already dispatches handlers on the main thread
 * (server-main for C→S, client-main for S→C), so we don't re-schedule.
 */
public final class NeoForgeNetworkChannel implements INetworkChannel {

    private record C2SRegistration<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {}

    private record S2CRegistration<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {}

    private final List<C2SRegistration<?>> pendingC2S = new ArrayList<>();
    private final List<S2CRegistration<?>> pendingS2C = new ArrayList<>();

    @Override
    public <T extends CustomPacketPayload> void registerClientToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {
        pendingC2S.add(new C2SRegistration<>(type, codec, handler));
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        // Lazy class-load: ClientPacketDistributor is client-only.
        // Server JVM never reaches this call site.
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(payload);
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {
        pendingS2C.add(new S2CRegistration<>(type, codec, handler));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * Apply every buffered registration against the live registrar. Called
     * exactly once per mod load from the {@code RegisterPayloadHandlersEvent}
     * subscriber in {@code AnimusMod}.
     */
    public void flushPending(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        for (C2SRegistration<?> r : pendingC2S) applyC2S(registrar, r);
        for (S2CRegistration<?> r : pendingS2C) applyS2C(registrar, r);
        pendingC2S.clear();
        pendingS2C.clear();
    }

    private static <T extends CustomPacketPayload> void applyC2S(
            PayloadRegistrar registrar, C2SRegistration<T> r) {
        registrar.playToServer(r.type(), r.codec(), (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer server) {
                r.handler().accept(payload, server);
            }
        });
    }

    private static <T extends CustomPacketPayload> void applyS2C(
            PayloadRegistrar registrar, S2CRegistration<T> r) {
        // playToClient registers a handler that fires on the client side
        // when a S→C packet of this type arrives. On a dedicated server,
        // the handler closure is never invoked (the server is the sender,
        // not the receiver).
        registrar.playToClient(r.type(), r.codec(), (payload, ctx) -> {
            r.handler().accept(payload);
        });
    }
}
