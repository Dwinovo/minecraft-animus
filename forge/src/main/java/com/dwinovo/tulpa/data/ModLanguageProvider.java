package com.dwinovo.tulpa.data;

import com.dwinovo.tulpa.Constants;
import net.minecraft.data.PackOutput;
import net.minecraftforge.common.data.LanguageProvider;

/**
 * Forge-side translation provider. One instance per locale; both feed the
 * shared {@link ModLanguageData} catalogue so the English and Simplified
 * Chinese JSONs stay in sync key-by-key. Mirrors {@code FabricModLanguageProvider}.
 */
public final class ModLanguageProvider extends LanguageProvider {

    private final String locale;

    public ModLanguageProvider(PackOutput output, String locale) {
        super(output, Constants.MOD_ID, locale);
        this.locale = locale;
    }

    @Override
    protected void addTranslations() {
        ModLanguageData.addTranslations(locale, this::add);
    }
}
