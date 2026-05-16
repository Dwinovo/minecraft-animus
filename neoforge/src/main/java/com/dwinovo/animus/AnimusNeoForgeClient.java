package com.dwinovo.animus;

import com.dwinovo.animus.agent.skill.BuiltinSkillBootstrap;
import com.dwinovo.animus.agent.skill.SkillRegistry;
import com.dwinovo.animus.anim.compile.BedrockResourceLoader;
import com.dwinovo.animus.entity.InitEntity;
import com.dwinovo.animus.render.AnimusRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.nio.file.Path;

@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public class AnimusNeoForgeClient {

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(InitEntity.ANIMUS.get(), AnimusRenderer::new);
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
