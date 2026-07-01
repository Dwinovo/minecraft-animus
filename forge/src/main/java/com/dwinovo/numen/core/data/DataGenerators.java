package com.dwinovo.numen.core.data;

import com.dwinovo.numen.core.Constants;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraftforge.common.data.ExistingFileHelper;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.CompletableFuture;

/**
 * Forge data-generation entry point for numen-core. Auto-registered via
 * {@link Mod.EventBusSubscriber} on the MOD bus; runs through
 * {@code ./gradlew :forge:runData}. Emits core's tags (the engine generates its
 * own GUI language separately). Outputs land in
 * {@code forge/src/generated/resources/}, already wired into the main resource
 * source set by the subproject's {@code build.gradle}.
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DataGenerators {

    private DataGenerators() {}

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();   // 1.20.4: PackOutput is on the generator, not the event
        CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();
        ExistingFileHelper efh = event.getExistingFileHelper();

        // Block tags first; the item tag provider copies from the block-tag output.
        ModBlockTagsProvider blockTags = new ModBlockTagsProvider(output, lookup, efh);
        generator.addProvider(event.includeServer(), blockTags);
        generator.addProvider(event.includeServer(),
                new ModItemTagsProvider(output, lookup, blockTags.contentsGetter(), efh));
    }
}
