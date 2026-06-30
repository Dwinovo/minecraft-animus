package com.dwinovo.numen.core;

import com.dwinovo.numen.core.pathing.cache.PathCaches;
import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.ScanBlocksJob;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Fabric entry point for the numen-core tool pack. Registers the tools and task
 * runners into the numen-api engine, then wires the server-tick work its tools
 * need (budget-sliced block scans, the off-thread pathfinder's chunk snapshots).
 * The engine itself (entity, agent loop, UI, network) is brought up by the
 * separate numen-api mod, which core depends on.
 */
public class NumenCoreFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        NumenCore.init();

        // Drive core's per-companion task queues each tick (move_to, mining, …).
        ServerTickEvents.END_SERVER_TICK.register(CompanionTickDispatcher::tick);
        // Advance budget-sliced long-range block scans each tick.
        ServerTickEvents.END_SERVER_TICK.register(ScanBlocksJob::tick);
        // Snapshot loaded chunks near companions for the off-thread planner to read live.
        ServerTickEvents.END_SERVER_TICK.register(PathCaches::serverTick);
        // Release those chunk references when the server stops (don't pin an old world's chunks).
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PathCaches.dropAll());

        Constants.LOG.info("numen-core initialised on Fabric.");
    }
}
