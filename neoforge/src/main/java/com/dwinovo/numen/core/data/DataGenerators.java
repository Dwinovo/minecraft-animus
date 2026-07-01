package com.dwinovo.numen.core.data;

import com.dwinovo.numen.core.Constants;
import net.minecraft.data.PackOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * NeoForge data-generation entry point for numen-core. Auto-registered via
 * {@link EventBusSubscriber}; runs through {@code ./gradlew :neoforge:runData}.
 * Emits core's tags (the engine generates its own GUI language separately).
 * Outputs land in {@code neoforge/src/generated/resources/}, already wired into
 * the main resource source set by the subproject's {@code build.gradle}.
 */
// 1.21.8: buses merged, so @EventBusSubscriber no longer takes a bus attribute (removed).
// GatherDataEvent.Client since the 1.21.4 Client/Server split.
@EventBusSubscriber(modid = Constants.MOD_ID)
public final class DataGenerators {

    private DataGenerators() {}

    @SubscribeEvent
    public static void gatherData(GatherDataEvent.Client event) {
        PackOutput output = event.getGenerator().getPackOutput();
        event.getGenerator().addProvider(true, new ModItemTagsProvider(output, event.getLookupProvider()));
        event.getGenerator().addProvider(true,
                new ModBlockTagsProvider(output, event.getLookupProvider()));
    }
}
