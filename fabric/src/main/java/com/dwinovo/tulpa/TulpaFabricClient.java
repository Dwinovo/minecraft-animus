package com.dwinovo.tulpa;

import com.dwinovo.tulpa.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.tulpa.agent.skill.SkillRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
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
                        BuiltinSkillBootstrap.bootstrap(tulpaConfigRoot, skillsDir);
                        SkillRegistry.instance().scan(skillsDir);
                    }
                });

        // G → companion roster panel (chat entry + settings/reset live in there).
        KeyBindingHelper.registerKeyBinding(com.dwinovo.tulpa.client.TulpaKeys.OPEN_ROSTER);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> {
                    com.dwinovo.tulpa.client.TulpaKeys.tick();
                    com.dwinovo.tulpa.client.hud.TulpaToasts.tick();
                });

        // HUD: advancement-style activity toasts (top-right) when not watching a panel.
        // 1.21.5 predates the HudElementRegistry layer API; use the classic HudRenderCallback.
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(
                (g, delta) -> com.dwinovo.tulpa.client.hud.TulpaToasts.render(g));

        // In-world path overlay for every companion (Baritone PathRenderer port),
        // drawn after translucent terrain so it sits over the world.
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES
                .register(ctx -> com.dwinovo.tulpa.client.path.PathVizRenderer.render(ctx.matrixStack()));

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
