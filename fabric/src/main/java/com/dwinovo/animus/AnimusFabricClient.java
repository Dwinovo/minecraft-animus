package com.dwinovo.animus;

import com.dwinovo.animus.anim.compile.BedrockResourceLoader;
import com.dwinovo.animus.entity.InitEntity;
import com.dwinovo.animus.render.AnimusRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
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
        EntityRendererRegistry.register(InitEntity.ANIMUS.get(), AnimusRenderer::new);

        Path configDir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID).resolve("models");
        BedrockResourceLoader loader = new BedrockResourceLoader(configDir);
        Identifier loaderId = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "anim_loader");
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return loaderId;
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager rm) {
                        loader.onResourceManagerReload(rm);
                    }
                });
    }
}
