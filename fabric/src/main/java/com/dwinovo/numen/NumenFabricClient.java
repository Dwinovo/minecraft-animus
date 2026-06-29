package com.dwinovo.numen;

import com.dwinovo.numen.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.nio.file.Path;

public class NumenFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Path numenConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path skillsDir = numenConfigRoot.resolve("skills");

        // Skills live under config/numen/skills. Hook the resource reload
        // pipeline so /reload picks up newly added SKILL.md files without a
        // client restart.
        ResourceLocation skillLoaderId = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "skill_loader");
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return skillLoaderId;
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager rm) {
                        // First-run only: extract built-in SKILL.md files from the jar
                        // into skillsDir. Subsequent /reload calls see the sentinel and
                        // skip — the directory becomes the player's after that.
                        BuiltinSkillBootstrap.bootstrap(numenConfigRoot, skillsDir);
                        SkillRegistry.instance().scan(skillsDir);
                    }
                });

        // G → companion roster panel (chat entry + settings/reset live in there).
        KeyBindingHelper.registerKeyBinding(com.dwinovo.numen.client.NumenKeys.OPEN_ROSTER);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> {
                    com.dwinovo.numen.client.NumenKeys.tick();
                    com.dwinovo.numen.client.hud.NumenToasts.tick();
                    com.dwinovo.numen.client.agent.AgentLoopRegistry.tickAll();
                });

        // HUD: advancement-style activity toasts (top-right) when not watching a panel.
        // 1.21.5 predates the HudElementRegistry layer API; use the classic HudRenderCallback.
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(
                (g, delta) -> com.dwinovo.numen.client.hud.NumenToasts.render(g));

        // In-world path overlay for every companion (Baritone PathRenderer port),
        // drawn after translucent terrain so it sits over the world.
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES
                .register(ctx -> com.dwinovo.numen.client.path.PathVizRenderer.render(ctx.matrixStack()));

        // Drop every path overlay on disconnect so a frozen path can't survive a
        // relog (the server can't send a clear to a player who's already gone).
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
                .register((handler, client) -> {
                    com.dwinovo.numen.client.path.ClientPathViz.clearAll();
                    com.dwinovo.numen.client.data.ClientNumenInventory.clear();
                    com.dwinovo.numen.client.hud.NumenToasts.clear();
                    com.dwinovo.numen.client.agent.ClientDeaths.clearAll();
                });
    }
}
