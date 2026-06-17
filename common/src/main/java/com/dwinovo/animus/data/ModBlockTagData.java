package com.dwinovo.animus.data;

import com.dwinovo.animus.data.ModItemTagData.TagAppenderProvider;
import com.dwinovo.animus.init.InitTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Single source of truth for every block tag the mod emits — mirrors
 * {@link ModItemTagData} but for the block registry. Both loaders' block-tag
 * providers forward here, so the content stays in {@code common/}.
 *
 * <h2>Adding a new tag</h2>
 * <ol>
 *   <li>Declare the {@code TagKey<Block>} in {@link InitTag}.</li>
 *   <li>Add a {@code tags.tag(InitTag.X).add(Blocks.Y)} block in {@link #addBlockTags}.</li>
 *   <li>Re-run {@code ./gradlew :fabric:runDatagen :neoforge:runData}.</li>
 * </ol>
 */
public final class ModBlockTagData {

    private ModBlockTagData() {}

    /**
     * Functional/valuable blocks the pathfinder must never break. We list only the
     * vanilla work stations that have NO block entity (so the
     * {@code shouldAvoidBreaking} BlockEntity proxy can't catch them); container
     * blocks (chests, furnaces, barrels, …) and modded machines stay covered by that
     * proxy. Mirrors the intent of Baritone's default {@code blocksToAvoidBreaking},
     * which likewise protects the crafting table. Packs can extend this tag freely.
     */
    public static void addBlockTags(TagAppenderProvider<Block> tags) {
        tags.tag(InitTag.DO_NOT_BREAK)
                .add(Blocks.CRAFTING_TABLE)
                .add(Blocks.STONECUTTER)
                .add(Blocks.SMITHING_TABLE)
                .add(Blocks.GRINDSTONE)
                .add(Blocks.LOOM)
                .add(Blocks.CARTOGRAPHY_TABLE)
                .add(Blocks.FLETCHING_TABLE)
                .add(Blocks.ANVIL)
                .add(Blocks.CHIPPED_ANVIL)
                .add(Blocks.DAMAGED_ANVIL);
    }
}
