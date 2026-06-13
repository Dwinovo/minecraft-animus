package com.dwinovo.animus.client.agent;

import com.dwinovo.animus.init.InitTicketType;
import com.dwinovo.animus.network.payload.KeepLoadedPayload;
import com.dwinovo.animus.platform.Services;

import java.util.UUID;

/**
 * Drives the owner-liveness heartbeat from the client tick. Once every
 * {@link InitTicketType#TASK_TICKET_REFRESH_TICKS} ticks it tells the server to
 * hold a chunk-ticket lease for every companion whose agent loop is mid-turn
 * ({@link AgentLoopRegistry#activeEntityUuids()}). This is what lets a far-away
 * pet stay loaded through the owner's LLM think-time without the server having
 * to GUESS how long that takes — the client, the only side that knows the loop
 * is still working, says so directly. When the loop goes idle the heartbeats
 * stop and the lease lapses on its own.
 *
 * <p>Client main thread only; both loaders' client-tick hooks call {@link #tick()}.
 * The send only fires when there is at least one active loop, which only happens
 * in-world, so there is never a send on a dead connection.
 */
public final class ClientHeartbeat {

    private static int counter;

    private ClientHeartbeat() {}

    public static void tick() {
        if (++counter < InitTicketType.TASK_TICKET_REFRESH_TICKS) return;
        counter = 0;
        for (UUID uuid : AgentLoopRegistry.activeEntityUuids()) {
            Services.NETWORK.sendToServer(new KeepLoadedPayload(uuid));
        }
    }
}
