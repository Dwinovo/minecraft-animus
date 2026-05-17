package com.dwinovo.animus;

import com.dwinovo.animus.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.animus.agent.skill.SkillRegistry;
import com.dwinovo.animus.anim.compile.BedrockResourceLoader;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.screen.AnimusManagerScreen;
import com.dwinovo.animus.client.screen.SettingsScreen;
import com.dwinovo.animus.entity.InitEntity;
import com.dwinovo.animus.render.AnimusRenderer;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public class AnimusNeoForgeClient {

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(InitEntity.ANIMUS.get(), AnimusRenderer::new);
    }

    /** Default keybind: {@code X} → open the Animus manager. */
    private static final KeyMapping OPEN_MANAGER = new KeyMapping(
            "key.animus.open_manager",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            KeyMapping.Category.MISC);

    @SubscribeEvent
    static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MANAGER);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (ClientTickEvent.Post ev) -> {
                    while (OPEN_MANAGER.consumeClick()) {
                        AnimusManagerScreen.open();
                    }
                });
    }

    @SubscribeEvent
    static void registerClientCommands(RegisterClientCommandsEvent event) {
        // Each verb is its own literal — no bare /animus <text> with a
        // greedy fallback, because Brigadier would let the greedy argument
        // swallow literals like "settings" registered later.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("animus")
                .executes(AnimusNeoForgeClient::cmdManager)
                .then(Commands.literal("settings").executes(AnimusNeoForgeClient::cmdSettings))
                .then(Commands.literal("reset").executes(AnimusNeoForgeClient::cmdReset))
                .then(Commands.literal("prompt").then(
                        Commands.argument("text", StringArgumentType.greedyString())
                                .executes(AnimusNeoForgeClient::cmdPrompt)))
                .then(Commands.literal("say").then(
                        Commands.argument("text", StringArgumentType.greedyString())
                                .executes(AnimusNeoForgeClient::cmdPrompt)));
        event.getDispatcher().register(root);
    }

    private static int cmdPrompt(CommandContext<CommandSourceStack> ctx) {
        String text = StringArgumentType.getString(ctx, "text");
        AgentLoopRegistry.playerAgent().submitPrompt(text);
        ctx.getSource().sendSuccess(() ->
                Component.literal("[animus] dispatched: " + truncate(text, 60)), false);
        return 1;
    }

    private static int cmdManager(CommandContext<CommandSourceStack> ctx) {
        AnimusManagerScreen.open();
        return 1;
    }

    private static int cmdSettings(CommandContext<CommandSourceStack> ctx) {
        SettingsScreen.open(null);
        return 1;
    }

    private static int cmdReset(CommandContext<CommandSourceStack> ctx) {
        AgentLoopRegistry.clear();
        ctx.getSource().sendSuccess(() ->
                Component.literal("[animus] conversation cleared"), false);
        return 1;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @SubscribeEvent
    static void registerReloadListeners(AddClientReloadListenersEvent event) {
        Path animusConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path modelsDir = animusConfigRoot.resolve("models");
        Path skillsDir = animusConfigRoot.resolve("skills");

        event.addListener(
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "anim_loader"),
                new BedrockResourceLoader(modelsDir));

        // Skills load via the same reload pipeline so /reload picks up
        // newly added SKILL.md files without a client restart. The bootstrap
        // call extracts mod-shipped built-in SKILL.md files into skillsDir on
        // first run; subsequent runs see the sentinel and skip.
        event.addListener(
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "skill_loader"),
                (ResourceManagerReloadListener) rm -> {
                    BuiltinSkillBootstrap.bootstrap(animusConfigRoot, skillsDir);
                    SkillRegistry.instance().scan(skillsDir);
                });
    }
}
