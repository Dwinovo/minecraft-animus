package com.dwinovo.tulpa.data;

import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;

/**
 * NeoForge-side item tag provider. Forwards to {@link ModItemTagData} so tag
 * content stays loader-agnostic in {@code common/}. Mirrors
 * {@code FabricModItemTagsProvider} in the fabric subproject.
 *
 * <p>1.21.5 has no {@code net.neoforged.neoforge.common.data.ItemTagsProvider}
 * (only {@code BlockTagsProvider}), so we extend the vanilla
 * {@link net.minecraft.data.tags.ItemTagsProvider}. It can copy block tags into
 * item tags via a block-tag lookup; we copy none, so an empty lookup is fed.
 */
public final class ModItemTagsProvider extends ItemTagsProvider {

    public ModItemTagsProvider(PackOutput output,
                               CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, lookupProvider, CompletableFuture.completedFuture(TagsProvider.TagLookup.empty()));
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // 1.21.5: vanilla tag(TagKey<T>) returns the protected IntrinsicTagAppender<T>
        // (add(T)); common can't name it, so wrap it as the neutral Appender here.
        ModItemTagData.addItemTags(key -> {
            var b = tag(key);
            return ModItemTagData.appender(v -> b.add(v));
        });
    }
}
