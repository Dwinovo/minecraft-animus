package com.dwinovo.animus;

import com.dwinovo.animus.network.AnimusNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;

public class AnimusMod implements ModInitializer {

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
                    com.dwinovo.animus.entity.Companions.syncRosterToOwner(server, player);
                });

        // Advance budget-sliced long-range block scans.
        ServerTickEvents.END_SERVER_TICK.register(
                com.dwinovo.animus.task.tasks.ScanBlocksJob::tick);
        // Drive companion player-body tasks (move_to / auto_mine) each tick.
        ServerTickEvents.END_SERVER_TICK.register(
                com.dwinovo.animus.task.CompanionTickDispatcher::tick);

        CommonClass.init();
        Constants.LOG.info("Animus mod initialised on Fabric.");
    }
}
