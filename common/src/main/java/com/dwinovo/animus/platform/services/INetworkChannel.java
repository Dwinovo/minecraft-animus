package com.dwinovo.animus.platform.services;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Cross-loader networking surface. Wraps Fabric's
 * {@code PayloadTypeRegistry} / {@code ServerPlayNetworking} /
 * {@code ClientPlayNetworking} and NeoForge's
 * {@code RegisterPayloadHandlersEvent} / {@code PacketDistributor} so feature
 * code in {@code common} can declare a payload, its codec, and its handler
 * once and have it work on both loaders.
 *
 * <h2>Payload lifecycle (C→S)</h2>
 * <ol>
 *   <li>Define a record implementing {@link CustomPacketPayload} with a
 *       public {@code Type<T>} and {@code StreamCodec}.</li>
 *   <li>Call {@link #registerClientToServer} once from {@code AnimusNetwork.register}.</li>
 *   <li>Client sends via {@link #sendToServer}; handler runs on the server
 *       main thread.</li>
 * </ol>
 *
 * <h2>Payload lifecycle (S→C)</h2>
 * <ol>
 *   <li>Same payload definition.</li>
 *   <li>Call {@link #registerServerToClient} once from {@code AnimusNetwork.register}.</li>
 *   <li>Server sends via {@link #sendToPlayer}; handler runs on the client
 *       main thread.</li>
 * </ol>
 *
 * <h2>Threading guarantee</h2>
 * Handlers (both directions) are dispatched on the receiving side's main
 * thread. Common code doesn't need to schedule.
 *
 * <h2>Why a single combined interface</h2>
 * Versus separate {@code IClientChannel} / {@code IServerChannel}: feature
 * code in common often needs to register both directions for one feature
 * (request + reply). Single channel lets {@code AnimusNetwork.register}
 * stay a flat list of registrations.
 */
public interface INetworkChannel {

    /**
     * Register a payload the client can send to the server. Idempotent per
     * type id — duplicate registrations are loader-defined error behaviour.
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
     * Send a registered payload from the client to the server. Client-only —
     * calling on the dedicated server throws.
     */
    void sendToServer(CustomPacketPayload payload);

    /**
     * Register a payload the server can send to clients. The {@code handler}
     * is invoked only on the client side — on a dedicated server JVM it is
     * never called, but the payload type still must be registered so the
     * server can serialise outbound packets. Implementations guard the
     * client-side handler hookup behind a side check, so the handler
     * lambda may reference client-only classes safely (they are lazy-loaded).
     *
     * @param type    payload type with a stable identifier
     * @param codec   stream codec for {@code RegistryFriendlyByteBuf}
     * @param handler invoked on the client main thread for each received payload
     */
    <T extends CustomPacketPayload> void registerServerToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler);

    /**
     * Send a registered payload from the server to a specific player.
     * Server-only — call from server-thread code.
     */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);
}
