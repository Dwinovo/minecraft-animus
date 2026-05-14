package com.dwinovo.animus.platform.services;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;

/**
 * Cross-loader networking surface. Wraps Fabric's
 * {@code PayloadTypeRegistry} / {@code ServerPlayNetworking} and NeoForge's
 * {@code RegisterPayloadHandlersEvent} / {@code PacketDistributor} so feature
 * code in {@code common} can declare a payload, its codec, and its handler
 * once, and have it work on both loaders.
 *
 * <h2>Payload lifecycle</h2>
 * <ol>
 *   <li>Define a payload record implementing {@link CustomPacketPayload} with
 *       a public {@code Type<T>} and {@code StreamCodec} (see
 *       {@code com.dwinovo.animus.network.payload.SetModelPayload}).</li>
 *   <li>From {@code common} (typically {@code AnimusNetwork.register}), call
 *       {@link #registerClientToServer} once per payload.</li>
 *   <li>Client sends via {@link #sendToServer}; the server handler runs on
 *       the main server thread.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * The handler is dispatched on the server's main thread — implementations
 * are responsible for posting back if the underlying API delivers on the
 * netty thread. Common code doesn't need to schedule.
 *
 * <h2>Server-to-client</h2>
 * Not yet exposed — only C→S is needed for the model-chooser GUI (vanilla
 * {@link net.minecraft.network.syncher.SynchedEntityData} carries the S→C
 * direction). The interface stays slim and gains an S→C method later when
 * a feature actually needs it.
 */
public interface INetworkChannel {

    /**
     * Register a payload type that the client can send to the server.
     * Idempotent per type id — calling twice with the same type yields
     * loader-defined behaviour (Fabric: throws; NeoForge: warns). Callers
     * register once at mod init.
     *
     * @param type    payload type with a stable identifier
     * @param codec   stream codec for {@code RegistryFriendlyByteBuf}
     * @param handler invoked on the server main thread for each received payload
     */
    <T extends CustomPacketPayload> void registerClientToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler);

    /**
     * Send a registered payload from the client to the server. Client-only;
     * calling on the dedicated server throws.
     */
    void sendToServer(CustomPacketPayload payload);
}
