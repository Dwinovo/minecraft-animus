package com.dwinovo.numen.core.init;

import com.dwinovo.numen.core.Constants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Catalogue of the datapack tags the numen-core tool pack declares. The
 * pathfinder and the loader-side data generators both reference the constants
 * here so the key's identifier exists in one place only — rename or repath in
 * this file and every consumer follows. These are core's tags (namespace
 * {@code numen}), not the engine's: pathfinding scaffolding and protected blocks
 * are tool-pack concerns.
 *
 * <h2>Why not derive at runtime</h2>
 * Tags are referenced from pathfinder hot paths where a fresh
 * {@link ResourceLocation#fromNamespaceAndPath} per call would allocate. Caching
 * the {@link TagKey} as a {@code static final} field amortises that cost
 * and gives the JIT a constant pool reference.
 */
public final class InitTag {

    /**
     * Foods that may be used to feed/heal a companion. Datapack-driven so server
     * admins can extend the list without code changes — see
     * {@code data/numen/tags/item/tame_foods.json}.
     */
    public static final TagKey<Item> TAME_FOODS = item("tame_foods");

    /**
     * Throwaway building blocks the pathfinder may consume as scaffolding while
     * travelling — bridging gaps, stepping up, and pillaring. The pathfinder only
     * ever places a block in this tag, so it never burns the player's valuables.
     * Datapack-driven so packs can add their own cheap blocks — see
     * {@code data/numen/tags/item/scaffolds.json}.
     */
    public static final TagKey<Item> SCAFFOLDS = item("scaffolds");

    /**
     * Blocks the pathfinder must never break while travelling — the player's
     * functional/valuable furniture. Any block in this tag gets {@code COST_INF},
     * so it's routed around (and a {@code move_to} onto one relaxes to "stand
     * adjacent" rather than digging it). This tag carries the no-BlockEntity work
     * stations (crafting table, stonecutter, smithing table, …) that the
     * BlockEntity proxy can't catch; container blocks are still covered by that
     * proxy on top. Datapack-driven so packs extend it freely — see
     * {@code data/numen/tags/block/do_not_break.json}.
     */
    public static final TagKey<Block> DO_NOT_BREAK = block("do_not_break");

    private InitTag() {}

    private static TagKey<Item> item(String name) {
        return TagKey.create(Registries.ITEM,
                new ResourceLocation(Constants.MOD_ID, name));
    }

    private static TagKey<Block> block(String name) {
        return TagKey.create(Registries.BLOCK,
                new ResourceLocation(Constants.MOD_ID, name));
    }
}
