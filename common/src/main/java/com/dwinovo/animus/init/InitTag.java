package com.dwinovo.animus.init;

import com.dwinovo.animus.Constants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Catalogue of every datapack tag the mod declares. Both the runtime entity
 * code and the loader-side data generators reference the constants here so
 * the key's identifier exists in one place only — rename or repath in this
 * file and every consumer follows.
 *
 * <h2>Why not derive at runtime</h2>
 * Tags are referenced from {@code mobInteract} hot paths where a fresh
 * {@link Identifier#fromNamespaceAndPath} per call would allocate. Caching
 * the {@link TagKey} as a {@code static final} field amortises that cost
 * and gives the JIT a constant pool reference.
 */
public final class InitTag {

    /**
     * Items players right-click an untamed Animus with to attempt taming, and
     * the same items also heal an already-tamed Animus when its owner feeds
     * it. Datapack-driven so server admins can extend the list without code
     * changes — see {@code data/animus/tags/item/tame_foods.json}.
     */
    public static final TagKey<Item> TAME_FOODS = item("tame_foods");

    private InitTag() {}

    private static TagKey<Item> item(String name) {
        return TagKey.create(Registries.ITEM,
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, name));
    }
}
