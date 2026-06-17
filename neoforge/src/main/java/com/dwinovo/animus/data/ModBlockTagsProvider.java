package com.dwinovo.animus.data;

import com.dwinovo.animus.Constants;
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
        ModBlockTagData.addBlockTags(this::tag);
    }
}
