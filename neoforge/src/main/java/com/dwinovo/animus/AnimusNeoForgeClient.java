package com.dwinovo.animus;

import com.dwinovo.animus.anim.compile.BedrockResourceLoader;
import com.dwinovo.animus.entity.InitEntity;
import com.dwinovo.animus.render.AnimusRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
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
        Path configDir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID).resolve("models");
        event.addListener(
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, "anim_loader"),
                new BedrockResourceLoader(configDir));
    }
}
