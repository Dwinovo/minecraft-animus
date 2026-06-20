package com.dwinovo.tulpa;

import com.dwinovo.tulpa.network.TulpaNetwork;
import com.dwinovo.tulpa.platform.ForgeTulpaConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;

/**
 * Forge mod entry for 1.20.4. Forge keeps separate mod and game event buses,
 * just like the NeoForge reference this was ported from — registration-type
 * events go on the mod bus (from the constructor here), while per-tick / world
 * lifecycle events go on {@link MinecraftForge#EVENT_BUS}.
 *
 * <p>Networking is registered eagerly via {@code TulpaNetwork.register()} — the
 * Forge {@link net.minecraftforge.network.SimpleChannel} accepts message
 * registration at any point during construction, so there is no deferred
 * "flush on RegisterPayloadHandlersEvent" dance like NeoForge required.
 */
@Mod(Constants.MOD_ID)
public class TulpaMod {

    public TulpaMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register the TOML config spec — Forge handles file creation +
        // hot-reload from here on. SPEC is built in the ForgeTulpaConfig static
        // initialiser (just data, no I/O), so referencing it now is safe.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ForgeTulpaConfig.SPEC);

        // Build the SimpleChannel and register every payload eagerly.
        TulpaNetwork.register();

        // Per-tick server work: long-range scans + companion task dispatch.
        MinecraftForge.EVENT_BUS.addListener(TulpaMod::onServerTickPost);
        // Dev: /tulpa_summon — create a companion fake player at the caller.
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) ->
                com.dwinovo.tulpa.entity.TulpaCommands.register(e.getDispatcher()));
        // When an owner logs in, bring their dormant companions back.
        MinecraftForge.EVENT_BUS.addListener(TulpaMod::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(TulpaMod::onPlayerChangedDimension);
        // Release pathfinding chunk-ref snapshots when the server stops (don't pin an old world).
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e) ->
                com.dwinovo.tulpa.pathing.cache.PathCaches.dropAll());

        // Client init (key mappings / HUD / world-render path overlay) is set up
        // from the client class, only on the physical client.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            TulpaForgeClient.init(modBus);
        }

        CommonClass.init();
        Constants.LOG.info("Tulpa mod initialised on Forge.");
    }

    private static void onServerTickPost(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        com.dwinovo.tulpa.task.tasks.ScanBlocksJob.tick(server);
        com.dwinovo.tulpa.task.CompanionTickDispatcher.tick(server);
        // Snapshot loaded chunks near companions each tick, for the off-thread planner to read live.
        com.dwinovo.tulpa.pathing.cache.PathCaches.serverTick(server);
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player instanceof com.dwinovo.tulpa.entity.TulpaPlayer) return;  // not the companion itself
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            com.dwinovo.tulpa.entity.Companions.respawnAllOwnedBy(server, player.getUUID());
            com.dwinovo.tulpa.entity.Companions.syncRosterToOwner(server, player);
        }
    }

    /** The companion crossed a portal on its own — tell its brain (ambient world event). */
    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof com.dwinovo.tulpa.entity.TulpaPlayer ap) {
            com.dwinovo.tulpa.entity.Companions.onDimensionChanged(ap);
        }
    }
}
