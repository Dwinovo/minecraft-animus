package com.dwinovo.animus;

import com.dwinovo.animus.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.animus.agent.skill.SkillRegistry;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.screen.SettingsScreen;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.nio.file.Path;

@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public class AnimusNeoForgeClient {

    @SubscribeEvent
    static void registerClientCommands(RegisterClientCommandsEvent event) {
        // Conversations happen per-companion (the roster panel's Chat entry).
        // /animus opens settings; /animus reset clears conversations.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("animus")
                .executes(AnimusNeoForgeClient::cmdSettings)
                .then(Commands.literal("settings").executes(AnimusNeoForgeClient::cmdSettings))
                .then(Commands.literal("reset").executes(AnimusNeoForgeClient::cmdReset));
        event.getDispatcher().register(root);
    }

    private static int cmdSettings(CommandContext<CommandSourceStack> ctx) {
        SettingsScreen.open(null);
        return 1;
    }

    private static int cmdReset(CommandContext<CommandSourceStack> ctx) {
        AgentLoopRegistry.clear();
        ctx.getSource().sendSuccess(() ->
                Component.literal("[animus] conversations cleared"), false);
        return 1;
    }

    @SubscribeEvent
    static void registerKeyMappings(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        // G → companion roster panel (remote chat entry; no need to stand by the body).
        event.register(com.dwinovo.animus.client.AnimusKeys.OPEN_ROSTER);
    }

    @SubscribeEvent
    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        com.dwinovo.animus.client.AnimusKeys.tick();
    }

    @SubscribeEvent
    static void registerReloadListeners(AddClientReloadListenersEvent event) {
        Path animusConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path skillsDir = animusConfigRoot.resolve("skills");

        event.addListener(
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "skill_loader"),
                (ResourceManagerReloadListener) rm -> {
                    BuiltinSkillBootstrap.bootstrap(animusConfigRoot, skillsDir);
                    SkillRegistry.instance().scan(skillsDir);
                });
    }
}
