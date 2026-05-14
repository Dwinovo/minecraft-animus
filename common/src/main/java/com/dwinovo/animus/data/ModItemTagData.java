package com.dwinovo.animus.data;

import com.dwinovo.animus.init.InitTag;
import net.minecraft.data.tags.TagAppender;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Single source of truth for every item tag the mod emits. Mirrors
 * {@link ModLanguageData}'s pattern: a {@link TagAppenderProvider} abstraction
 * lets the Fabric and NeoForge data providers feed their own native builder
 * here, so adding a new tag entry edits one place and both loaders pick it up.
 *
 * <h2>Adding a new tag</h2>
 * <ol>
 *   <li>Declare the {@link TagKey} in {@link InitTag}.</li>
 *   <li>Add a {@code tags.tag(InitTag.X).add(Items.Y)} block in
 *       {@link #addItemTags}.</li>
 *   <li>Re-run {@code ./gradlew :fabric:runDatagen :neoforge:runData}.</li>
 * </ol>
 */
public final class ModItemTagData {

    private ModItemTagData() {}

    /**
     * Loader-agnostic adapter: each loader's tag provider implements this to
     * return its own {@code TagAppender} for a given key. The signature
     * matches Mojang's {@code valueLookupBuilder} so adapter classes can
     * pass {@code this::valueLookupBuilder} directly.
     */
    @FunctionalInterface
    public interface TagAppenderProvider<T> {
        TagAppender<T, T> tag(TagKey<T> key);
    }

    /**
     * Foods players right-click an untamed Animus with to attempt taming, and
     * the same set heals an already-tamed Animus when its owner feeds it.
     * Mirrors chiikawa's tame-food list (vanilla foods only, no mod
     * cross-deps).
     */
    public static void addItemTags(TagAppenderProvider<Item> tags) {
        tags.tag(InitTag.TAME_FOODS)
                .add(Items.APPLE)
                .add(Items.BAKED_POTATO)
                .add(Items.BREAD)
                .add(Items.CARROT)
                .add(Items.COOKED_BEEF)
                .add(Items.COOKED_CHICKEN)
                .add(Items.COOKED_COD)
                .add(Items.COOKED_MUTTON)
                .add(Items.COOKED_PORKCHOP)
                .add(Items.COOKED_RABBIT)
                .add(Items.COOKED_SALMON)
                .add(Items.COOKIE)
                .add(Items.GLOW_BERRIES)
                .add(Items.GOLDEN_APPLE)
                .add(Items.GOLDEN_CARROT)
                .add(Items.HONEY_BOTTLE)
                .add(Items.MELON_SLICE)
                .add(Items.MUSHROOM_STEW)
                .add(Items.PUMPKIN_PIE)
                .add(Items.POTATO)
                .add(Items.BEETROOT)
                .add(Items.RABBIT_STEW)
                .add(Items.SWEET_BERRIES);
    }
}
