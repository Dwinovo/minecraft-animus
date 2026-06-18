package com.dwinovo.tulpa;

import com.dwinovo.tulpa.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.tulpa.agent.skill.SkillRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;

import java.nio.file.Path;

@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public class TulpaNeoForgeClient {

    @SubscribeEvent
    static void registerKeyMappings(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        // G → companion roster panel (chat entry + settings/reset live in there).
        event.register(com.dwinovo.tulpa.client.TulpaKeys.OPEN_ROSTER);
    }

    @SubscribeEvent
    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        com.dwinovo.tulpa.client.TulpaKeys.tick();
        com.dwinovo.tulpa.client.hud.TulpaToasts.tick();
    }

    @SubscribeEvent
    static void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterTranslucentFeatures event) {
        // In-world path overlay for every companion (Baritone PathRenderer port).
        com.dwinovo.tulpa.client.path.PathVizRenderer.render(event.getPoseStack());
    }

    @SubscribeEvent
    static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        // Drop every path overlay on disconnect so a frozen path can't survive a relog.
        com.dwinovo.tulpa.client.path.ClientPathViz.clearAll();
        com.dwinovo.tulpa.client.data.ClientTulpaInventory.clear();
        com.dwinovo.tulpa.client.hud.TulpaToasts.clear();
        com.dwinovo.tulpa.client.agent.ClientDeaths.clearAll();
    }

    @SubscribeEvent
    static void registerGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        // HUD: advancement-style activity toasts (top-right) when not watching a panel.
        event.registerAboveAll(
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "tulpa_toasts"),
                (g, delta) -> com.dwinovo.tulpa.client.hud.TulpaToasts.render(g));
    }

    @SubscribeEvent
    static void registerReloadListeners(AddClientReloadListenersEvent event) {
        Path tulpaConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path skillsDir = tulpaConfigRoot.resolve("skills");

        event.addListener(
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "skill_loader"),
                (ResourceManagerReloadListener) rm -> {
                    BuiltinSkillBootstrap.bootstrap(tulpaConfigRoot, skillsDir);
                    SkillRegistry.instance().scan(skillsDir);
                });
    }
}
