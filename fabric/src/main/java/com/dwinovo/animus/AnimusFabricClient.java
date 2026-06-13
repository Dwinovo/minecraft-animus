package com.dwinovo.animus;

import com.dwinovo.animus.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.animus.agent.skill.SkillRegistry;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.screen.SettingsScreen;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.nio.file.Path;

public class AnimusFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Path animusConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path skillsDir = animusConfigRoot.resolve("skills");

        // Skills live under config/animus/skills. Hook the resource reload
        // pipeline so /reload picks up newly added SKILL.md files without a
        // client restart.
        Identifier skillLoaderId = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "skill_loader");
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return skillLoaderId;
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager rm) {
                        // First-run only: extract built-in SKILL.md files from the jar
                        // into skillsDir. Subsequent /reload calls see the sentinel and
                        // skip — the directory becomes the player's after that.
                        BuiltinSkillBootstrap.bootstrap(animusConfigRoot, skillsDir);
                        SkillRegistry.instance().scan(skillsDir);
                    }
                });

        // G → companion roster panel (remote chat entry; no need to stand by the body).
        KeyMappingHelper.registerKeyMapping(com.dwinovo.animus.client.AnimusKeys.OPEN_ROSTER);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> com.dwinovo.animus.client.AnimusKeys.tick());

        registerClientCommands();
    }

    /**
     * Register the {@code /animus} client commands. All branches run
     * entirely client-side — they open client GUIs or reset agent state.
     * Conversations happen per-companion (the roster panel's Chat entry).
     */
    private static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root =
                    net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("animus")
                            // /animus (no args) → open settings (configure API key).
                            .executes(ctx -> {
                                SettingsScreen.open(null);
                                return 1;
                            });

            // /animus settings  — explicit alias
            root.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                    .literal("settings")
                    .executes(ctx -> {
                        SettingsScreen.open(null);
                        return 1;
                    }));

            // /animus reset  — clear all per-entity conversations
            root.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                    .literal("reset")
                    .executes(ctx -> {
                        AgentLoopRegistry.clear();
                        ctx.getSource().sendFeedback(Component.literal("[animus] conversations cleared"));
                        return 1;
                    }));

            dispatcher.register(root);
        });
    }
}
