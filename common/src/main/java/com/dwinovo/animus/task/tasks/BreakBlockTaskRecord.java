package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;
import net.minecraft.core.BlockPos;

/**
 * Typed task descriptor for {@code break_block}: walk to and break ONE exact
 * block cell. The precision complement to {@code place_block} for construction
 * work; {@code auto_mine} stays the bulk by-type gatherer.
 */
public final class BreakBlockTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "break_block";

    public final BlockPos target;

    public BreakBlockTaskRecord(String toolCallId, long deadlineGameTime, BlockPos target) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.target = target;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + target.getX() + "," + target.getY() + "," + target.getZ();
    }
}
