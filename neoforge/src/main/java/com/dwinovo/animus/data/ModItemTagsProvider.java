package com.dwinovo.animus.data;

import com.dwinovo.animus.Constants;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.ItemTagsProvider;

/**
 * NeoForge-side item tag provider. Forwards to {@link ModItemTagData} so tag
 * content stays loader-agnostic in {@code common/}. Mirrors
 * {@code FabricModItemTagsProvider} in the fabric subproject (use of {@code @code}
 * over {@code @link} because that class lives outside this subproject's
 * javadoc classpath).
 */
public final class ModItemTagsProvider extends ItemTagsProvider {

    public ModItemTagsProvider(PackOutput output,
                               CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, lookupProvider, Constants.MOD_ID);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // Vanilla MC's protected `tag(TagKey<T>)` returns TagAppender<T, T>,
        // matching ModItemTagData.TagAppenderProvider's signature. Fabric
        // exposes the same thing under the alias `valueLookupBuilder`; we
        // call the vanilla name here so the NeoForge code path doesn't
        // depend on any Fabric-specific extension.
        ModItemTagData.addItemTags(this::tag);
    }
}
