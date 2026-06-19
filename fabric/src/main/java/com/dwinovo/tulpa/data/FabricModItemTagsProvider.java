package com.dwinovo.tulpa.data;

import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagProvider;
import net.minecraft.core.HolderLookup;

/**
 * Fabric-side item tag provider. Forwards to {@link ModItemTagData} so the
 * tag content lives in {@code common/} and stays loader-agnostic. Mirrors
 * the {@link FabricModLanguageProvider} pattern.
 */
public final class FabricModItemTagsProvider extends FabricTagProvider.ItemTagProvider {

    public FabricModItemTagsProvider(FabricDataOutput output,
                                     CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // 1.21.5 has no valueLookupBuilder/TagAppender<T,T>; wrap Fabric's
        // getOrCreateTagBuilder (FabricTagBuilder.add(T)) as the neutral Appender.
        ModItemTagData.addItemTags(key -> {
            var b = getOrCreateTagBuilder(key);
            return ModItemTagData.appender(v -> b.add(v));
        });
    }
}
