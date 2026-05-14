package com.dwinovo.animus.platform;

import com.dwinovo.animus.platform.services.INetworkChannel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * NeoForge implementation of {@link INetworkChannel}. NeoForge requires
 * payload registration to happen during the {@code RegisterPayloadHandlersEvent}
 * window, so this channel buffers registrations into a pending queue and
 * flushes them when {@link #flushPending(RegisterPayloadHandlersEvent)} is
 * invoked from the event handler (see {@code AnimusMod}).
 *
 * <p>{@code IPayloadContext} already dispatches handlers on the main thread,
 * so we don't need to re-schedule manually.
 */
public final class NeoForgeNetworkChannel implements INetworkChannel {

    private record Registration<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {}

    private final List<Registration<?>> pending = new ArrayList<>();

    @Override
    public <T extends CustomPacketPayload> void registerClientToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {
        pending.add(new Registration<>(type, codec, handler));
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        // Lazy class-load: ClientPacketDistributor is client-only.
        // Server JVM never reaches this call site.
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(payload);
    }

    /**
     * Apply every buffered registration against the live registrar. Called
     * exactly once per mod load from the {@code RegisterPayloadHandlersEvent}
     * subscriber in {@code AnimusMod}.
     */
    public void flushPending(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        for (Registration<?> r : pending) {
            applyOne(registrar, r);
        }
        pending.clear();
    }

    private static <T extends CustomPacketPayload> void applyOne(
            PayloadRegistrar registrar, Registration<T> r) {
        registrar.playToServer(r.type(), r.codec(), (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer server) {
                r.handler().accept(payload, server);
            }
        });
    }
}
