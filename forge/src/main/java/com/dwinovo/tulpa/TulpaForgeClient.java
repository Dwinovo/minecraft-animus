package com.dwinovo.tulpa;

import com.dwinovo.tulpa.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.tulpa.agent.skill.SkillRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import java.nio.file.Path;

/**
 * Client entry point for Forge 1.20.4. Forge keeps separate mod and game event
 * buses (mirroring the NeoForge reference this was ported from): registration
 * events (key mappings / GUI overlays / reload listeners) go on the mod bus,
 * while the per-tick / world-render / disconnect hooks go on the game bus
 * ({@link MinecraftForge#EVENT_BUS}).
 *
 * <p>Invoked from {@link TulpaMod} only when {@code FMLEnvironment.dist} is the
 * physical client, so none of these client-only types load on a dedicated server.
 */
public final class TulpaForgeClient {

    private TulpaForgeClient() {}

    /** Wire every client listener. {@code modBus} is the mod event bus from the constructor. */
    public static void init(IEventBus modBus) {
        // Mod bus — registration events.
        modBus.addListener(TulpaForgeClient::registerKeyMappings);
        modBus.addListener(TulpaForgeClient::registerGuiOverlays);
        modBus.addListener(TulpaForgeClient::registerReloadListeners);
        // Game bus — per-tick / world-render / disconnect.
        MinecraftForge.EVENT_BUS.addListener(TulpaForgeClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(TulpaForgeClient::onRenderLevel);
        MinecraftForge.EVENT_BUS.addListener(TulpaForgeClient::onLoggingOut);
    }

    static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        // G → companion roster panel (chat entry + settings/reset live in there).
        event.register(com.dwinovo.tulpa.client.TulpaKeys.OPEN_ROSTER);
    }

    static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        com.dwinovo.tulpa.client.TulpaKeys.tick();
        com.dwinovo.tulpa.client.hud.TulpaToasts.tick();
    }

    static void onRenderLevel(RenderLevelStageEvent event) {
        // Draw after translucent terrain so the overlay sits over the world.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        // In-world path overlay for every companion (Baritone PathRenderer port).
        com.dwinovo.tulpa.client.path.PathVizRenderer.render(event.getPoseStack());
    }

    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Drop every path overlay on disconnect so a frozen path can't survive a relog.
        com.dwinovo.tulpa.client.path.ClientPathViz.clearAll();
        com.dwinovo.tulpa.client.data.ClientTulpaInventory.clear();
        com.dwinovo.tulpa.client.hud.TulpaToasts.clear();
        com.dwinovo.tulpa.client.agent.ClientDeaths.clearAll();
    }

    static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
        // HUD: advancement-style activity toasts (top-right) when not watching a panel.
        // Forge 1.20.4 overlay API: register above the whole vanilla HUD layer stack.
        event.registerAbove(VanillaGuiOverlay.CHAT_PANEL.id(), "tulpa_toasts",
                (gui, g, partialTick, screenWidth, screenHeight) ->
                        com.dwinovo.tulpa.client.hud.TulpaToasts.render(g));
    }

    static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        Path tulpaConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path skillsDir = tulpaConfigRoot.resolve("skills");

        event.registerReloadListener((ResourceManagerReloadListener) rm -> {
            BuiltinSkillBootstrap.bootstrap(tulpaConfigRoot, skillsDir);
            SkillRegistry.instance().scan(skillsDir);
        });
    }
}
