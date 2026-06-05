package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * Typed task descriptor for {@code place_block}: "put one {@code block} at
 * {@code pos}." The goal ({@link PlaceBlockTaskGoal}) pathfinds to a standable
 * spot next to the target (bridging/digging like move_to), then places the block
 * from the entity's inventory. Refuses floating placements (needs a solid
 * neighbour to attach to) and occupied targets.
 */
public final class PlaceBlockTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "place_block";

    /** The block to place (already validated as a placeable BlockItem's block). */
    public final Block block;
    /** The block the LLM holds in inventory (one is consumed on success). */
    public final net.minecraft.world.item.Item item;
    /** Target cell to fill. */
    public final BlockPos pos;
    /** Human-readable label for messages / debug overlay (e.g. "torch"). */
    public final String label;

    public PlaceBlockTaskRecord(String toolCallId, long deadlineGameTime,
                                Block block, net.minecraft.world.item.Item item,
                                BlockPos pos, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.block = block;
        this.item = item;
        this.pos = pos;
        this.label = label;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + label + " @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
