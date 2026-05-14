package com.dwinovo.animus.network;

import com.dwinovo.animus.network.payload.AnimusPromptPayload;
import com.dwinovo.animus.network.payload.SetModelPayload;
import com.dwinovo.animus.platform.Services;

/**
 * Central registration hub for every {@link
 * net.minecraft.network.protocol.common.custom.CustomPacketPayload} the mod
 * declares. Each loader's mod-init code calls {@link #register} exactly once
 * during startup; the {@link Services#NETWORK} platform implementation takes
 * care of the loader-specific timing (Fabric registers immediately; NeoForge
 * queues until {@code RegisterPayloadHandlersEvent} fires).
 *
 * <p>Adding a new payload:
 * <ol>
 *   <li>Define a record under {@code com.dwinovo.animus.network.payload}
 *       implementing {@code CustomPacketPayload} with a public
 *       {@code Type} and {@code StreamCodec}.</li>
 *   <li>Add one {@code Services.NETWORK.registerClientToServer(...)} call
 *       here (and an S→C equivalent when that direction lands).</li>
 *   <li>Send from client via {@code Services.NETWORK.sendToServer(...)}.</li>
 * </ol>
 */
public final class AnimusNetwork {

    private AnimusNetwork() {}

    public static void register() {
        Services.NETWORK.registerClientToServer(
                SetModelPayload.TYPE, SetModelPayload.STREAM_CODEC, SetModelPayload::handle);
        Services.NETWORK.registerClientToServer(
                AnimusPromptPayload.TYPE, AnimusPromptPayload.STREAM_CODEC, AnimusPromptPayload::handle);
    }
}
