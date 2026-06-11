package com.dwinovo.animus.task.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/** Typed task descriptor for {@code deposit_items} — see {@link ContainerItemTaskRecord}. */
public final class DepositItemsTaskRecord extends ContainerItemTaskRecord {

    public static final String TOOL_NAME = "deposit_items";

    public DepositItemsTaskRecord(String toolCallId, long deadlineGameTime,
                                  Item item, int count, String label,
                                  BlockPos target, int searchRadius) {
        super(TOOL_NAME, toolCallId, deadlineGameTime, item, count, label, target, searchRadius);
    }
}
