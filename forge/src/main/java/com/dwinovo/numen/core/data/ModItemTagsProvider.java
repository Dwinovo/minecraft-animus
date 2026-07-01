package com.dwinovo.numen.core.data;

import com.dwinovo.numen.core.Constants;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/**
 * Forge-side item tag provider. Forwards to {@link ModItemTagData} so tag
 * content stays loader-agnostic in {@code common/}.
 *
 * <p>Forge patches the vanilla {@link ItemTagsProvider} with a constructor that
 * takes a {@code modId} + {@link ExistingFileHelper}. The block-tag lookup is
 * supplied so item tags can copy from block tags; we copy none.
 */
public final class ModItemTagsProvider extends ItemTagsProvider {

    public ModItemTagsProvider(PackOutput output,
                               CompletableFuture<HolderLookup.Provider> lookupProvider,
                               CompletableFuture<TagsProvider.TagLookup<net.minecraft.world.level.block.Block>> blockTags,
                               ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, blockTags, Constants.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        ModItemTagData.addItemTags(key -> {
            var b = tag(key);
            return ModItemTagData.appender(v -> b.add(v));
        });
    }
}
