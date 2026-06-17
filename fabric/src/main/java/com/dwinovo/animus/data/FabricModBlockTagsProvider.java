package com.dwinovo.animus.data;

import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;
import net.minecraft.core.HolderLookup;

/**
 * Fabric-side block tag provider. Forwards to {@link ModBlockTagData} so the tag
 * content lives in {@code common/} and stays loader-agnostic. Mirrors
 * {@link FabricModItemTagsProvider}.
 */
public final class FabricModBlockTagsProvider extends FabricTagsProvider.BlockTagsProvider {

    public FabricModBlockTagsProvider(FabricPackOutput output,
                                      CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        ModBlockTagData.addBlockTags(this::valueLookupBuilder);
    }
}
