package com.dwinovo.animus;

import com.dwinovo.animus.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.animus.agent.skill.SkillRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.nio.file.Path;

public class AnimusFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Path animusConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path skillsDir = animusConfigRoot.resolve("skills");

        // Skills live under config/animus/skills. Hook the resource reload
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
                        BuiltinSkillBootstrap.bootstrap(animusConfigRoot, skillsDir);
                        SkillRegistry.instance().scan(skillsDir);
                    }
                });

        // G → companion roster panel (chat entry + settings/reset live in there).
        KeyMappingHelper.registerKeyMapping(com.dwinovo.animus.client.AnimusKeys.OPEN_ROSTER);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> com.dwinovo.animus.client.AnimusKeys.tick());
    }
}
