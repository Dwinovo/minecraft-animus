package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * Typed task descriptor for {@code drop_items}: toss {@code count} of
 * {@code item} out of the entity's inventory onto the ground.
 */
public final class DropItemsTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "drop_items";

    public final Item item;
    public final int count;
    public final String label;

    public DropItemsTaskRecord(String toolCallId, long deadlineGameTime,
                               Item item, int count, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.item = item;
        this.count = count;
        this.label = label;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + count + "x " + label;
    }
}
