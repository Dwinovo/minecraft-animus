package com.dwinovo.animus.network;

import com.dwinovo.animus.network.payload.ExecuteToolPayload;
import com.dwinovo.animus.network.payload.SetModelPayload;
import com.dwinovo.animus.network.payload.TaskResultPayload;
import com.dwinovo.animus.platform.Services;

/**
 * Central registration hub for every {@link
 * net.minecraft.network.protocol.common.custom.CustomPacketPayload} the mod
 * declares. Each loader's mod-init code calls {@link #register} exactly once
 * during startup; the {@link Services#NETWORK} platform implementation takes
 * care of the loader-specific timing (Fabric registers immediately; NeoForge
 * queues until {@code RegisterPayloadHandlersEvent} fires).
 *
 * <h2>Adding a new payload</h2>
 * <ol>
 *   <li>Define a record under {@code com.dwinovo.animus.network.payload}
 *       implementing {@code CustomPacketPayload} with a public
 *       {@code Type} and {@code StreamCodec}.</li>
 *   <li>Add one {@code Services.NETWORK.registerClientToServer(...)} or
 *       {@code registerServerToClient(...)} call here.</li>
 *   <li>Send from the appropriate side via
 *       {@code Services.NETWORK.sendToServer(...)} or
 *       {@code Services.NETWORK.sendToPlayer(...)}.</li>
 * </ol>
 */
public final class AnimusNetwork {

    private AnimusNetwork() {}

    public static void register() {
        // Client → Server: player chose a model in the chooser GUI.
        Services.NETWORK.registerClientToServer(
                SetModelPayload.TYPE, SetModelPayload.STREAM_CODEC, SetModelPayload::handle);

        // Client → Server: the client-side LLM emitted a tool_call; execute on owner's entity.
        Services.NETWORK.registerClientToServer(
                ExecuteToolPayload.TYPE, ExecuteToolPayload.STREAM_CODEC, ExecuteToolPayload::handle);

        // Server → Client: tool execution finished; ship result back to the owner.
        Services.NETWORK.registerServerToClient(
                TaskResultPayload.TYPE, TaskResultPayload.STREAM_CODEC, TaskResultPayload::handle);
    }
}
