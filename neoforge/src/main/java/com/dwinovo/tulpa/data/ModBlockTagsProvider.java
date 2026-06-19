package com.dwinovo.tulpa.data;

import com.dwinovo.tulpa.Constants;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.BlockTagsProvider;

/**
 * NeoForge-side block tag provider. Forwards to {@link ModBlockTagData} so tag
 * content stays loader-agnostic in {@code common/}. Mirrors
 * {@code FabricModBlockTagsProvider} in the fabric subproject.
 */
public final class ModBlockTagsProvider extends BlockTagsProvider {

    public ModBlockTagsProvider(PackOutput output,
                                CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, lookupProvider, Constants.MOD_ID);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // 1.21.5: vanilla tag(TagKey<T>) returns the protected IntrinsicTagAppender<T>
        // (add(T)); common can't name it, so wrap it as the neutral Appender here.
        ModBlockTagData.addBlockTags(key -> {
            var b = tag(key);
            return ModItemTagData.appender(v -> b.add(v));
        });
    }
}
