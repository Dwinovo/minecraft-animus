package com.dwinovo.numen.core.data;

import com.dwinovo.numen.core.Constants;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraftforge.common.data.BlockTagsProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/**
 * Forge-side block tag provider. Forwards to {@link ModBlockTagData} so tag
 * content stays loader-agnostic in {@code common/}.
 */
public final class ModBlockTagsProvider extends BlockTagsProvider {

    public ModBlockTagsProvider(PackOutput output,
                                CompletableFuture<HolderLookup.Provider> lookupProvider,
                                ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, Constants.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        ModBlockTagData.addBlockTags(key -> {
            var b = tag(key);
            return ModItemTagData.appender(v -> b.add(v));
        });
    }
}
