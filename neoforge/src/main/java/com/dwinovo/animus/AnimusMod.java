package com.dwinovo.animus;

import com.dwinovo.animus.entity.AnimusDimensionFollow;
import com.dwinovo.animus.network.AnimusNetwork;
import com.dwinovo.animus.platform.NeoForgeAnimusConfig;
import com.dwinovo.animus.platform.NeoForgeNetworkChannel;
import com.dwinovo.animus.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(Constants.MOD_ID)
public class AnimusMod {

    public AnimusMod(IEventBus eventBus, ModContainer container) {
        eventBus.addListener(AnimusMod::registerPayloads);

        // Register the TOML config spec — NeoForge handles file creation +
        // hot-reload from this point on. SPEC is built lazily in the
        // NeoForgeAnimusConfig static initialiser so referencing it here is
        // safe (no I/O happens until the world loads).
        container.registerConfig(ModConfig.Type.COMMON, NeoForgeAnimusConfig.SPEC);

        // Queue payload registrations into NeoForgeNetworkChannel; the queue
        // flushes when RegisterPayloadHandlersEvent fires (see below).
        AnimusNetwork.register();

        // Game-bus (not mod-bus) listener: bring owned companions along when the
        // owner crosses a dimension.
        NeoForge.EVENT_BUS.addListener(AnimusMod::onPlayerChangedDimension);
        // Per-tick server work: long-range scans + companion task dispatch.
        NeoForge.EVENT_BUS.addListener(AnimusMod::onServerTickPost);
        // Dev: /animus_summon — create a companion fake player at the caller.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent e) ->
                com.dwinovo.animus.entity.AnimusCommands.register(e.getDispatcher()));
        // When an owner logs in, bring their dormant companions back.
        NeoForge.EVENT_BUS.addListener(AnimusMod::onPlayerLoggedIn);

        CommonClass.init();
        Constants.LOG.info("Animus mod initialised on NeoForge.");
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        if (Services.NETWORK instanceof NeoForgeNetworkChannel ch) {
            ch.flushPending(event);
        }
    }

    private static void onServerTickPost(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        com.dwinovo.animus.task.tasks.ScanBlocksJob.tick(event.getServer());
        com.dwinovo.animus.task.CompanionTickDispatcher.tick(event.getServer());
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player instanceof com.dwinovo.animus.entity.AnimusPlayer) return;  // not the companion itself
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            com.dwinovo.animus.entity.Companions.respawnAllOwnedBy(server, player.getUUID());
        }
    }

    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        ServerLevel from = server.getLevel(event.getFrom());
        ServerLevel to = server.getLevel(event.getTo());
        if (from != null && to != null) {
            AnimusDimensionFollow.onOwnerChangedDimension(player, from, to);
        }
    }
}
