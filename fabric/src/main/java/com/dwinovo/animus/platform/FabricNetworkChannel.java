package com.dwinovo.animus.platform;

import com.dwinovo.animus.platform.services.INetworkChannel;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;

/**
 * Fabric implementation of {@link INetworkChannel}. Registration is immediate
 * (Fabric's {@code PayloadTypeRegistry} is available throughout startup);
 * incoming handlers are bounced onto the server main thread via
 * {@code MinecraftServer.execute} so common-code handlers don't have to
 * worry about netty-thread access.
 */
public final class FabricNetworkChannel implements INetworkChannel {

    @Override
    public <T extends CustomPacketPayload> void registerClientToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) -> {
            MinecraftServer server = context.server();
            ServerPlayer player = context.player();
            server.execute(() -> handler.accept(payload, player));
        });
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        // Lazy class-load: ClientPlayNetworking is client-only.
        // Server JVM never reaches this call site.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(payload);
    }
}
