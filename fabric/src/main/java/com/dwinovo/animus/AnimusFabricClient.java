package com.dwinovo.animus;

import com.dwinovo.animus.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.animus.agent.skill.SkillRegistry;
import com.dwinovo.animus.anim.compile.BedrockResourceLoader;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.screen.SettingsScreen;
import com.dwinovo.animus.entity.InitEntity;
import com.dwinovo.animus.render.AnimusRenderer;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.nio.file.Path;

public class AnimusFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(InitEntity.ANIMUS.get(), AnimusRenderer::new);

        Path animusConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path modelsDir = animusConfigRoot.resolve("models");
        Path skillsDir = animusConfigRoot.resolve("skills");

        BedrockResourceLoader loader = new BedrockResourceLoader(modelsDir);
        Identifier loaderId = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "anim_loader");
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return loaderId;
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager rm) {
                        loader.onResourceManagerReload(rm);
                    }
                });

        // Skills live alongside models. Hook into the same reload pipeline so
        // /reload picks up newly added SKILL.md files without a client restart.
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

        registerClientCommands();
    }

    /**
     * Register the {@code /animus} client commands. All branches run
     * entirely client-side — they open client GUIs or reset agent state.
     * Conversations now happen per-entity (right-click an Animus, or the
     * Units tab Chat button), so there is no prompt verb here.
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

            // /animus debug  — toggle the head overlay (current task above each pet)
            root.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                    .literal("debug")
                    .executes(ctx -> {
                        boolean on = com.dwinovo.animus.client.debug.AnimusDebugState.toggle();
                        ctx.getSource().sendFeedback(Component.literal(
                                "[animus] debug overlay " + (on ? "on" : "off")));
                        return 1;
                    }));

            dispatcher.register(root);
        });
    }
}
