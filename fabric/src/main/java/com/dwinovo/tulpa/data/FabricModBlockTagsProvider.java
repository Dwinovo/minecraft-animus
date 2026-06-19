package com.dwinovo.tulpa.data;

import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagProvider;
import net.minecraft.core.HolderLookup;

/**
 * Fabric-side block tag provider. Forwards to {@link ModBlockTagData} so the tag
 * content lives in {@code common/} and stays loader-agnostic. Mirrors
 * {@link FabricModItemTagsProvider}.
 */
public final class FabricModBlockTagsProvider extends FabricTagProvider.BlockTagProvider {

    public FabricModBlockTagsProvider(FabricDataOutput output,
                                      CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // 1.21.5 has no valueLookupBuilder/TagAppender<T,T>; wrap Fabric's
        // getOrCreateTagBuilder (FabricTagBuilder.add(T)) as the neutral Appender.
        ModBlockTagData.addBlockTags(key -> {
            var b = getOrCreateTagBuilder(key);
            return ModItemTagData.appender(v -> b.add(v));
        });
    }
}
