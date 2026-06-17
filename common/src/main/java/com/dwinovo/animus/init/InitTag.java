package com.dwinovo.animus.init;

import com.dwinovo.animus.Constants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

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

    /**
     * Throwaway building blocks the Animus may consume as scaffolding while
     * pathfinding — bridging gaps, stepping up, and pillaring. The pathfinder
     * ({@link com.dwinovo.animus.pathing}) only ever places a block in this tag,
     * so it never burns the player's valuables. Datapack-driven so packs can add
     * their own cheap blocks — see {@code data/animus/tags/item/scaffolds.json}.
     */
    public static final TagKey<Item> SCAFFOLDS = item("scaffolds");

    /**
     * Blocks the pathfinder must never break while travelling — the player's
     * functional/valuable furniture. {@link com.dwinovo.animus.pathing.util.BlockHelper#shouldAvoidBreaking}
     * gives any block in this tag {@code COST_INF}, so it's routed around (and a
     * {@code move_to} onto one relaxes to "stand adjacent" rather than digging it).
     * This tag carries the no-BlockEntity work stations (crafting table, stonecutter,
     * smithing table, …) that the BlockEntity proxy can't catch; container blocks are
     * still covered by that proxy on top. Datapack-driven so packs extend it freely —
     * see {@code data/animus/tags/block/do_not_break.json}.
     */
    public static final TagKey<Block> DO_NOT_BREAK = block("do_not_break");

    private InitTag() {}

    private static TagKey<Item> item(String name) {
        return TagKey.create(Registries.ITEM,
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, name));
    }

    private static TagKey<Block> block(String name) {
        return TagKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath(Constants.MOD_ID, name));
    }
}
