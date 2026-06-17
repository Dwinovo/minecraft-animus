package com.dwinovo.animus.network;

import com.dwinovo.animus.network.payload.AnimusDeathPayload;
import com.dwinovo.animus.network.payload.AnimusLocationsPayload;
import com.dwinovo.animus.network.payload.LocateAnimusPayload;
import com.dwinovo.animus.network.payload.CancelTasksPayload;
import com.dwinovo.animus.network.payload.ClientUiActionPayload;
import com.dwinovo.animus.network.payload.CompanionListPayload;
import com.dwinovo.animus.network.payload.ExecuteToolPayload;
import com.dwinovo.animus.network.payload.PathVizPayload;
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
        // C→S: the client-side LLM emitted a tool_call; execute on the owner's Animus.
        Services.NETWORK.registerClientToServer(
                ExecuteToolPayload.TYPE, ExecuteToolPayload.STREAM_CODEC, ExecuteToolPayload::handle);

        // C→S: owner pressed Stop — cancel the running + queued tasks (body stop).
        Services.NETWORK.registerClientToServer(
                CancelTasksPayload.TYPE, CancelTasksPayload.STREAM_CODEC, CancelTasksPayload::handle);

        // S→C: tool execution finished; ship the result back to the owner.
        Services.NETWORK.registerServerToClient(
                TaskResultPayload.TYPE, TaskResultPayload.STREAM_CODEC, TaskResultPayload::handle);

        // S→C: an Animus body died; suspend the owner's agent loop (resolves the in-flight
        // tool call with the death cause). Recoverable — see AnimusRespawnPayload.
        Services.NETWORK.registerServerToClient(
                AnimusDeathPayload.TYPE, AnimusDeathPayload.STREAM_CODEC,
                AnimusDeathPayload::handle);

        // S→C: the dead companion has respawned at its owner; resume the suspended loop.
        Services.NETWORK.registerServerToClient(
                com.dwinovo.animus.network.payload.AnimusRespawnPayload.TYPE,
                com.dwinovo.animus.network.payload.AnimusRespawnPayload.STREAM_CODEC,
                com.dwinovo.animus.network.payload.AnimusRespawnPayload::handle);

        // S→C: a generic async world event (dimension change, hazard, …) for a companion's brain.
        Services.NETWORK.registerServerToClient(
                com.dwinovo.animus.network.payload.AnimusEventPayload.TYPE,
                com.dwinovo.animus.network.payload.AnimusEventPayload.STREAM_CODEC,
                com.dwinovo.animus.network.payload.AnimusEventPayload::handle);

        // S→C: the owner's companion roster (UUID + name), pushed on login + summon
        // so the client panel knows which fake players are its companions.
        Services.NETWORK.registerServerToClient(
                CompanionListPayload.TYPE, CompanionListPayload.STREAM_CODEC,
                CompanionListPayload::handle);

        // S→C: the companion's current pathfinding plan, for the in-world path
        // overlay (Baritone PathRenderer, ported to our server-authored path).
        Services.NETWORK.registerServerToClient(
                PathVizPayload.TYPE, PathVizPayload.STREAM_CODEC,
                PathVizPayload::handle);

        // S→C: server `/animus` verbs that must act on the caller's own client
        // (open settings GUI / reset conversations).
        Services.NETWORK.registerServerToClient(
                ClientUiActionPayload.TYPE, ClientUiActionPayload.STREAM_CODEC,
                ClientUiActionPayload::handle);

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
