package com.dwinovo.animus;

import com.dwinovo.animus.entity.AnimusDimensionFollow;
import com.dwinovo.animus.network.AnimusNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AnimusMod implements ModInitializer {

    /**
     * Last-known dimension per online player. Fabric API 5.x removed
     * {@code ServerEntityWorldChangeEvents}, so we detect a dimension change by
     * polling each tick (a handful of players, one key compare each) and fire
     * the companion-follow when a player's dimension key changes.
     */
    private static final Map<UUID, ResourceKey<Level>> LAST_DIM = new HashMap<>();

    @Override
    public void onInitialize() {
        AnimusNetwork.register();

        // Dev: /animus_summon — create a companion fake player at the caller.
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, env) ->
                        com.dwinovo.animus.entity.AnimusCommands.register(dispatcher));

        // When an owner logs in, bring their dormant companions back.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> {
                    ServerPlayer player = handler.getPlayer();
                    if (player instanceof com.dwinovo.animus.entity.AnimusPlayer) return;  // not the companion itself
                    com.dwinovo.animus.entity.Companions.respawnAllOwnedBy(server, player.getUUID());
                });

        // Bring owned companions along when the owner crosses a dimension.
        ServerTickEvents.END_SERVER_TICK.register(AnimusMod::detectDimensionChanges);
        // Advance budget-sliced long-range block scans.
        ServerTickEvents.END_SERVER_TICK.register(
                com.dwinovo.animus.task.tasks.ScanBlocksJob::tick);
        // Drive companion player-body tasks (move_to / auto_mine) each tick.
        ServerTickEvents.END_SERVER_TICK.register(
                com.dwinovo.animus.task.CompanionTickDispatcher::tick);

        CommonClass.init();
        Constants.LOG.info("Animus mod initialised on Fabric.");
    }

    private static void detectDimensionChanges(net.minecraft.server.MinecraftServer server) {
        LAST_DIM.keySet().retainAll(
                server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ResourceKey<Level> current = player.level().dimension();
            ResourceKey<Level> previous = LAST_DIM.put(player.getUUID(), current);
            if (previous != null && !previous.equals(current)) {
                ServerLevel from = server.getLevel(previous);
                ServerLevel to = server.getLevel(current);
                if (from != null && to != null) {
                    AnimusDimensionFollow.onOwnerChangedDimension(player, from, to);
                }
            }
        }
    }
}
