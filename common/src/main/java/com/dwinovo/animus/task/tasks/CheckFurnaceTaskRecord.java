package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;
import net.minecraft.core.BlockPos;

/**
 * Typed task descriptor for {@code check_furnace}: read the working state of the
 * furnace at {@code pos} (lit?, input/fuel/output slots, rough ETA) and report
 * it. Read-only and location-independent — the goal ({@link CheckFurnaceTaskGoal})
 * does no pathfinding, so the entity can poll a furnace's progress from anywhere
 * while it's off doing other work.
 */
public final class CheckFurnaceTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "check_furnace";

    /** The furnace position to inspect (from a prior load_furnace result). */
    public final BlockPos pos;

    public CheckFurnaceTaskRecord(String toolCallId, long deadlineGameTime, BlockPos pos) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.pos = pos;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
