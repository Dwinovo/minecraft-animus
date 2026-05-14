package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;

/**
 * Typed task descriptor for the {@code move_to} tool. Carries the target
 * coordinates and walking speed multiplier; the deadline-based timeout is
 * handled by the base class.
 */
public final class MoveToTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "move_to";

    public final double x;
    public final double y;
    public final double z;
    /** PathNavigation speed multiplier; 1.0 ≈ entity's MOVEMENT_SPEED attribute. */
    public final double speed;

    public MoveToTaskRecord(String toolCallId, long deadlineGameTime,
                            double x, double y, double z, double speed) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.x = x;
        this.y = y;
        this.z = z;
        this.speed = speed;
    }
}
