package com.dwinovo.numen;

import com.dwinovo.numen.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/**
 * Client entry point. 1.21.4 still has SEPARATE mod and game event buses
 * (1.21.5 merged them), so a single {@code @EventBusSubscriber} can't carry both
 * — registration events (key mappings / GUI layers / reload listeners) are mod-bus,
 * the tick / world-render / disconnect hooks are game-bus. We register each on its
 * own bus from the mod constructor, mirroring {@link NumenMod}.
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class NumenNeoForgeClient {

    public NumenNeoForgeClient(IEventBus modBus) {
        // Mod bus — registration events.
        modBus.addListener(NumenNeoForgeClient::registerKeyMappings);
        modBus.addListener(NumenNeoForgeClient::registerGuiLayers);
        modBus.addListener(NumenNeoForgeClient::registerReloadListeners);
        // Game bus — per-tick / world-render / disconnect.
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(NumenNeoForgeClient::onLoggingOut);
    }

    static void registerKeyMappings(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        // G → companion roster panel (chat entry + settings/reset live in there).
        event.register(com.dwinovo.numen.client.NumenKeys.OPEN_ROSTER);
    }

    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        com.dwinovo.numen.client.NumenKeys.tick();
        com.dwinovo.numen.client.hud.NumenToasts.tick();
        com.dwinovo.numen.client.agent.AgentLoopRegistry.tickAll();
    }

    static void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        // 1.21.5 predates the per-stage AfterTranslucentBlocks event subclass; gate on the Stage enum.
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        // In-world path overlay for every companion (Baritone PathRenderer port).
        com.dwinovo.numen.client.path.PathVizRenderer.render(event.getPoseStack());
    }

    static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        // Drop every path overlay on disconnect so a frozen path can't survive a relog.
        com.dwinovo.numen.client.path.ClientPathViz.clearAll();
        com.dwinovo.numen.client.data.ClientNumenInventory.clear();
        com.dwinovo.numen.client.hud.NumenToasts.clear();
        com.dwinovo.numen.client.agent.ClientDeaths.clearAll();
    }

    static void registerGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        // HUD: advancement-style activity toasts (top-right) when not watching a panel.
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "numen_toasts"),
                (g, delta) -> com.dwinovo.numen.client.hud.NumenToasts.render(g));
    }

    static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        // 1.21.1 uses RegisterClientReloadListenersEvent.registerReloadListener(listener) — no
        // ResourceLocation key (that's the 1.21.4 AddClientReloadListenersEvent API).
        Path numenConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path skillsDir = numenConfigRoot.resolve("skills");

        event.registerReloadListener((ResourceManagerReloadListener) rm -> {
            BuiltinSkillBootstrap.bootstrap(numenConfigRoot, skillsDir);
            SkillRegistry.instance().scan(skillsDir);
        });
    }
}
