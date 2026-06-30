package com.dwinovo.numen.core;

import com.dwinovo.numen.core.pathing.cache.PathCaches;
import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.ScanBlocksJob;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge entry point for the numen-core tool pack. Registers the tools and
 * task runners into the numen-api engine, then wires the server-tick work its
 * tools need (budget-sliced block scans, the off-thread pathfinder's chunk
 * snapshots). The engine itself is brought up by the separate numen-api mod,
 * which core depends on.
 */
@Mod(Constants.MOD_ID)
public class NumenCoreNeoForge {

    public NumenCoreNeoForge(IEventBus eventBus, ModContainer container) {
        NumenCore.init();

        NeoForge.EVENT_BUS.addListener(NumenCoreNeoForge::onServerTickPost);
        // Release pathfinding chunk-ref snapshots when the server stops (don't pin an old world).
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> PathCaches.dropAll());

        Constants.LOG.info("numen-core initialised on NeoForge.");
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        CompanionTickDispatcher.tick(event.getServer());
        ScanBlocksJob.tick(event.getServer());
        PathCaches.serverTick(event.getServer());
    }
}
