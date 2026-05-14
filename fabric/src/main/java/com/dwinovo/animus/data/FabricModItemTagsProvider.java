package com.dwinovo.animus.data;

import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;
import net.minecraft.core.HolderLookup;

/**
 * Fabric-side item tag provider. Forwards to {@link ModItemTagData} so the
 * tag content lives in {@code common/} and stays loader-agnostic. Mirrors
 * the {@link FabricModLanguageProvider} pattern.
 */
public final class FabricModItemTagsProvider extends FabricTagsProvider.ItemTagsProvider {

    public FabricModItemTagsProvider(FabricPackOutput output,
                                     CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        ModItemTagData.addItemTags(this::valueLookupBuilder);
    }
}
