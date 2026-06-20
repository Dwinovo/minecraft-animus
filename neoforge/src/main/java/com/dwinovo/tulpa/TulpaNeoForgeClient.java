package com.dwinovo.tulpa;

import com.dwinovo.tulpa.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.tulpa.agent.skill.SkillRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/**
 * Client entry point. 1.21.4 still has SEPARATE mod and game event buses
 * (1.21.5 merged them), so a single {@code @EventBusSubscriber} can't carry both
 * — registration events (key mappings / GUI layers / reload listeners) are mod-bus,
 * the tick / world-render / disconnect hooks are game-bus. We register each on its
 * own bus from the mod constructor, mirroring {@link TulpaMod}.
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class TulpaNeoForgeClient {

    public TulpaNeoForgeClient(IEventBus modBus) {
        // Mod bus — registration events.
        modBus.addListener(TulpaNeoForgeClient::registerKeyMappings);
        modBus.addListener(TulpaNeoForgeClient::registerGuiLayers);
        modBus.addListener(TulpaNeoForgeClient::registerReloadListeners);
        // Game bus — per-tick / world-render / disconnect.
        NeoForge.EVENT_BUS.addListener(TulpaNeoForgeClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TulpaNeoForgeClient::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(TulpaNeoForgeClient::onLoggingOut);
    }

    static void registerKeyMappings(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        // G → companion roster panel (chat entry + settings/reset live in there).
        event.register(com.dwinovo.tulpa.client.TulpaKeys.OPEN_ROSTER);
    }

    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        com.dwinovo.tulpa.client.TulpaKeys.tick();
        com.dwinovo.tulpa.client.hud.TulpaToasts.tick();
    }

    static void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        // 1.21.5 predates the per-stage AfterTranslucentBlocks event subclass; gate on the Stage enum.
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        // In-world path overlay for every companion (Baritone PathRenderer port).
        com.dwinovo.tulpa.client.path.PathVizRenderer.render(event.getPoseStack());
    }

    static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        // Drop every path overlay on disconnect so a frozen path can't survive a relog.
        com.dwinovo.tulpa.client.path.ClientPathViz.clearAll();
        com.dwinovo.tulpa.client.data.ClientTulpaInventory.clear();
        com.dwinovo.tulpa.client.hud.TulpaToasts.clear();
        com.dwinovo.tulpa.client.agent.ClientDeaths.clearAll();
    }

    static void registerGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        // HUD: advancement-style activity toasts (top-right) when not watching a panel.
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "tulpa_toasts"),
                (g, delta) -> com.dwinovo.tulpa.client.hud.TulpaToasts.render(g));
    }

    static void registerReloadListeners(AddClientReloadListenersEvent event) {
        Path tulpaConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path skillsDir = tulpaConfigRoot.resolve("skills");

        event.addListener(
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "skill_loader"),
                (ResourceManagerReloadListener) rm -> {
                    BuiltinSkillBootstrap.bootstrap(tulpaConfigRoot, skillsDir);
                    SkillRegistry.instance().scan(skillsDir);
                });
    }
}
