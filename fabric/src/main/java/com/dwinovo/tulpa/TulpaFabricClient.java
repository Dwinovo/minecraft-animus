package com.dwinovo.tulpa;

import com.dwinovo.tulpa.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.tulpa.agent.skill.SkillRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.nio.file.Path;

public class TulpaFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Path tulpaConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path skillsDir = tulpaConfigRoot.resolve("skills");

        // Skills live under config/tulpa/skills. Hook the resource reload
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
                        BuiltinSkillBootstrap.bootstrap(tulpaConfigRoot, skillsDir);
                        SkillRegistry.instance().scan(skillsDir);
                    }
                });

        // G → companion roster panel (chat entry + settings/reset live in there).
        KeyMappingHelper.registerKeyMapping(com.dwinovo.tulpa.client.TulpaKeys.OPEN_ROSTER);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> {
                    com.dwinovo.tulpa.client.TulpaKeys.tick();
                    com.dwinovo.tulpa.client.hud.TulpaToasts.tick();
                });

        // HUD: advancement-style activity toasts (top-right) when not watching a panel.
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "tulpa_toasts"),
                (g, delta) -> com.dwinovo.tulpa.client.hud.TulpaToasts.render(g));

        // In-world path overlay for every companion (Baritone PathRenderer port),
        // drawn after translucent terrain so it sits over the world.
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES
                .register(ctx -> com.dwinovo.tulpa.client.path.PathVizRenderer.render(ctx.poseStack()));

        // Drop every path overlay on disconnect so a frozen path can't survive a
        // relog (the server can't send a clear to a player who's already gone).
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
                .register((handler, client) -> {
                    com.dwinovo.tulpa.client.path.ClientPathViz.clearAll();
                    com.dwinovo.tulpa.client.data.ClientTulpaInventory.clear();
                    com.dwinovo.tulpa.client.hud.TulpaToasts.clear();
                    com.dwinovo.tulpa.client.agent.ClientDeaths.clearAll();
                });
    }
}
