package com.dwinovo.animus.data;

import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.core.HolderLookup;

/**
 * Fabric-side translation provider. One instance per locale; both feed the
 * shared {@link ModLanguageData} catalogue so the English and Simplified
 * Chinese JSONs stay in sync key-by-key.
 */
public final class FabricModLanguageProvider extends FabricLanguageProvider {

    private final String locale;

    public FabricModLanguageProvider(FabricPackOutput output, String locale,
                                     CompletableFuture<HolderLookup.Provider> registries) {
        super(output, locale, registries);
        this.locale = locale;
    }

    @Override
    public void generateTranslations(HolderLookup.Provider registries, TranslationBuilder builder) {
        ModLanguageData.addTranslations(locale, builder::add);
    }
}
