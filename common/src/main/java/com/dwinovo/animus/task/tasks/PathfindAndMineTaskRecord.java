package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;
import net.minecraft.core.BlockPos;

/**
 * Typed task descriptor for the {@code pathfind_and_mine} tool. Combines
 * the move-to-reach phase with the mine phase in a single LLM-facing op,
 * sparing the LLM the two-step ReAct of {@code move_to → mine_block}.
 *
 * <p>Pre-checks via {@link BlockMiningProgress#checkMineable} run at goal
 * start, so unmineable targets (bedrock / air) fail fast before any
 * path-finding is attempted — no wasted walks.
 */
public final class PathfindAndMineTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "pathfind_and_mine";

    public final BlockPos pos;
    /** PathNavigation speed multiplier; 1.0 ≈ entity's MOVEMENT_SPEED attribute. */
    public final double speed;

    public PathfindAndMineTaskRecord(String toolCallId, long deadlineGameTime,
                                     BlockPos pos, double speed) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.pos = pos;
        this.speed = speed;
    }
}
