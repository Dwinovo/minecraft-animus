package com.dwinovo.animus;

import com.dwinovo.animus.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.animus.agent.skill.SkillRegistry;
import com.dwinovo.animus.anim.compile.BedrockResourceLoader;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.screen.SettingsScreen;
import com.dwinovo.animus.entity.InitEntity;
import com.dwinovo.animus.render.AnimusRenderer;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
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
     * Register the {@code /animus} client commands. All three branches run
     * entirely client-side — they only manipulate the {@code PlayerAgentLoop}
     * and open client GUIs.
     */
    private static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root =
                    net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("animus");

            // /animus <prompt...>  — alias for /animus prompt
            root.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                    .argument("text", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String text = StringArgumentType.getString(ctx, "text");
                        AgentLoopRegistry.playerAgent().submitPrompt(text);
                        ctx.getSource().sendFeedback(Component.literal("[animus] dispatched: " + truncate(text, 60)));
                        return 1;
                    }));

            // /animus settings  — open settings GUI
            root.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                    .literal("settings")
                    .executes(ctx -> {
                        Minecraft.getInstance().execute(() -> SettingsScreen.open(null));
                        return 1;
                    }));

            // /animus reset  — clear PlayerAgent conversation
            root.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                    .literal("reset")
                    .executes(ctx -> {
                        AgentLoopRegistry.clear();
                        ctx.getSource().sendFeedback(Component.literal("[animus] conversation cleared"));
                        return 1;
                    }));

            dispatcher.register(root);
        });
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
