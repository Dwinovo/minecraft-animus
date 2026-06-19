package com.dwinovo.tulpa.data;

import com.dwinovo.tulpa.init.InitTag;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.function.Consumer;

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
     * return an {@link Appender} for a given key. On 1.21.5 the only MC appender
     * with a {@code add(T)} sink ({@code IntrinsicHolderTagsProvider.IntrinsicTagAppender})
     * is {@code protected}, and the public {@code TagsProvider.TagAppender} only
     * takes {@code ResourceKey}s — and the 1.21.6+ two-param {@code TagAppender} /
     * {@code valueLookupBuilder} alias doesn't exist yet. So common defines its own
     * neutral sink and each loader wraps its native builder via {@link #appender}.
     */
    @FunctionalInterface
    public interface TagAppenderProvider<T> {
        Appender<T> tag(TagKey<T> key);
    }

    /** Minimal fluent sink — only the {@code .add(T)} chaining the tag lists use. */
    @FunctionalInterface
    public interface Appender<T> {
        Appender<T> add(T value);
    }

    /** Adapt a native MC tag builder's {@code add} to an {@link Appender}; loaders pass
     *  {@code v -> nativeBuilder.add(v)} (an explicit lambda, not a method ref, to dodge
     *  the {@code add(T)} vs {@code add(T...)} overload ambiguity). */
    public static <T> Appender<T> appender(Consumer<T> add) {
        return new Appender<>() {
            @Override
            public Appender<T> add(T value) {
                add.accept(value);
                return this;
            }
        };
    }

    /**
     * Foods players right-click an untamed Tulpa with to attempt taming, and
     * the same set heals an already-tamed Tulpa when its owner feeds it.
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

        // Cheap, common blocks the pathfinder may throw away as scaffolding.
        // Mirrors Baritone's acceptableThrowawayItems — never the player's
        // valuables. Packs can extend this tag freely.
        tags.tag(InitTag.SCAFFOLDS)
                .add(Items.COBBLESTONE)
                .add(Items.DIRT)
                .add(Items.COBBLED_DEEPSLATE)
                .add(Items.STONE)
                .add(Items.NETHERRACK)
                .add(Items.ANDESITE)
                .add(Items.DIORITE)
                .add(Items.GRANITE)
                .add(Items.TUFF)
                .add(Items.DEEPSLATE);
    }
}
