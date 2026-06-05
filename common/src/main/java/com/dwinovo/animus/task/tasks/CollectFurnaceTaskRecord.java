package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;
import net.minecraft.core.BlockPos;

/**
 * Typed task descriptor for {@code collect_furnace}: walk to the furnace at
 * {@code pos} and take its finished output into the entity's inventory. The goal
 * ({@link CollectFurnaceTaskGoal}) pathfinds there (bridging/digging like
 * move_to), empties the result slot, and reports what it took plus the furnace's
 * remaining state.
 */
public final class CollectFurnaceTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "collect_furnace";

    /** The furnace position to collect from (from a prior load_furnace result). */
    public final BlockPos pos;

    public CollectFurnaceTaskRecord(String toolCallId, long deadlineGameTime, BlockPos pos) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.pos = pos;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
