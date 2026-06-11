package com.dwinovo.animus.network;

import com.dwinovo.animus.network.payload.AnimusDeathPayload;
import com.dwinovo.animus.network.payload.AnimusLocationsPayload;
import com.dwinovo.animus.network.payload.LocateAnimusPayload;
import com.dwinovo.animus.network.payload.CancelTasksPayload;
import com.dwinovo.animus.network.payload.ExecuteToolPayload;
import com.dwinovo.animus.network.payload.OpenAnimusInventoryPayload;
import com.dwinovo.animus.network.payload.SetModelPayload;
import com.dwinovo.animus.network.payload.TaskResultPayload;
import com.dwinovo.animus.platform.Services;

/**
 * Central registration hub for every {@link
 * net.minecraft.network.protocol.common.custom.CustomPacketPayload} the mod
 * declares. Each loader's mod-init code calls {@link #register} exactly once
 * during startup; the {@link Services#NETWORK} platform implementation handles
 * the loader-specific timing.
 *
 * <h2>Adding a new payload</h2>
 * <ol>
 *   <li>Define a record under {@code com.dwinovo.animus.network.payload}
 *       implementing {@code CustomPacketPayload} with a public {@code Type}
 *       and {@code StreamCodec}.</li>
 *   <li>Add one {@code registerClientToServer(...)} or
 *       {@code registerServerToClient(...)} call here.</li>
 * </ol>
 */
public final class AnimusNetwork {

    private AnimusNetwork() {}

    public static void register() {
        // C→S: owner picked a model in the GUI's model tab.
        Services.NETWORK.registerClientToServer(
                SetModelPayload.TYPE, SetModelPayload.STREAM_CODEC, SetModelPayload::handle);

        // C→S: the client-side LLM emitted a tool_call; execute on the owner's Animus.
        Services.NETWORK.registerClientToServer(
                ExecuteToolPayload.TYPE, ExecuteToolPayload.STREAM_CODEC, ExecuteToolPayload::handle);

        // C→S: owner pressed Stop — cancel the running + queued tasks (body stop).
        Services.NETWORK.registerClientToServer(
                CancelTasksPayload.TYPE, CancelTasksPayload.STREAM_CODEC, CancelTasksPayload::handle);

        // S→C: tool execution finished; ship the result back to the owner.
        Services.NETWORK.registerServerToClient(
                TaskResultPayload.TYPE, TaskResultPayload.STREAM_CODEC, TaskResultPayload::handle);

        // S→C: an Animus body died for good; hard-stop the owner's agent loop.
        Services.NETWORK.registerServerToClient(
                AnimusDeathPayload.TYPE, AnimusDeathPayload.STREAM_CODEC,
                AnimusDeathPayload::handle);

        // C→S: open one Animus's inventory as a vanilla chest menu.
        Services.NETWORK.registerClientToServer(
                OpenAnimusInventoryPayload.TYPE, OpenAnimusInventoryPayload.STREAM_CODEC,
                OpenAnimusInventoryPayload::handle);

        // C→S: roster panel asks where its (possibly far / cross-dimension) pets are.
        Services.NETWORK.registerClientToServer(
                LocateAnimusPayload.TYPE, LocateAnimusPayload.STREAM_CODEC,
                LocateAnimusPayload::handle);

        // S→C: locate answers — position/dimension/HP snapshots per pet.
        Services.NETWORK.registerServerToClient(
                AnimusLocationsPayload.TYPE, AnimusLocationsPayload.STREAM_CODEC,
                AnimusLocationsPayload::handle);
    }
}
