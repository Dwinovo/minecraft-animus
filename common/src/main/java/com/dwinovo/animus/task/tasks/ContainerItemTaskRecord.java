package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * Shared shape of the chest-storage task descriptors ({@code deposit_items} /
 * {@code take_items}): which item, how many, and which container — explicit
 * coordinates or auto-pick within {@code searchRadius}.
 */
public abstract class ContainerItemTaskRecord extends TaskRecord {

    public final Item item;
    public final int count;
    /** Human-readable item label for messages / debug overlay. */
    public final String label;
    /** Explicit container to use, or {@code null} for nearest chest/barrel. */
    public final BlockPos target;
    /** Auto-pick search radius in blocks. */
    public final int searchRadius;

    protected ContainerItemTaskRecord(String toolName, String toolCallId, long deadlineGameTime,
                                      Item item, int count, String label,
                                      BlockPos target, int searchRadius) {
        super(toolName, toolCallId, deadlineGameTime);
        this.item = item;
        this.count = count;
        this.label = label;
        this.target = target;
        this.searchRadius = searchRadius;
    }

    @Override
    public String describe() {
        return getToolName() + " " + count + "x " + label;
    }
}
