package com.dwinovo.tulpa.data;

import com.dwinovo.tulpa.Constants;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraftforge.common.data.BlockTagsProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/**
 * Forge-side block tag provider. Forwards to {@link ModBlockTagData} so tag
 * content stays loader-agnostic in {@code common/}. Mirrors
 * {@code FabricModBlockTagsProvider} in the fabric subproject.
 */
public final class ModBlockTagsProvider extends BlockTagsProvider {

    public ModBlockTagsProvider(PackOutput output,
                                CompletableFuture<HolderLookup.Provider> lookupProvider,
                                ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, Constants.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // common can't name the provider's protected appender type; wrap each
        // builder's add(T) as the neutral Appender here.
        ModBlockTagData.addBlockTags(key -> {
            var b = tag(key);
            return ModItemTagData.appender(v -> b.add(v));
        });
    }
}
