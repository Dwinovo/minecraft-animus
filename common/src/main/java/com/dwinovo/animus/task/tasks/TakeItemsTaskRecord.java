package com.dwinovo.animus.task.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/** Typed task descriptor for {@code take_items} — see {@link ContainerItemTaskRecord}. */
public final class TakeItemsTaskRecord extends ContainerItemTaskRecord {

    public static final String TOOL_NAME = "take_items";

    public TakeItemsTaskRecord(String toolCallId, long deadlineGameTime,
                               Item item, int count, String label,
                               BlockPos target, int searchRadius) {
        super(TOOL_NAME, toolCallId, deadlineGameTime, item, count, label, target, searchRadius);
    }
}
