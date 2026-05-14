package com.dwinovo.animus.data;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/**
 * Fabric data-generation entry point. Wired up via the {@code fabric-datagen}
 * entrypoint in {@code fabric.mod.json}; runs through
 * {@code ./gradlew :fabric:runDatagen}.
 *
 * <p>Outputs land in {@code fabric/src/generated/resources/}, which the
 * Fabric subproject's {@code build.gradle} adds back to the main resource
 * source set so the JSONs ship inside the jar.
 */
public final class FabricDataGenerators implements DataGeneratorEntrypoint {

    @Override
    public void onInitializeDataGenerator(FabricDataGenerator generator) {
        FabricDataGenerator.Pack pack = generator.createPack();
        pack.addProvider((output, registries) -> new FabricModLanguageProvider(output, "en_us", registries));
        pack.addProvider((output, registries) -> new FabricModLanguageProvider(output, "zh_cn", registries));
        pack.addProvider(FabricModItemTagsProvider::new);
    }
}
