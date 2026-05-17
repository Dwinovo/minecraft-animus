package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;

/**
 * Typed task descriptor for the {@code attack_target} tool. Carries the
 * vanilla entity id of whom to attack; everything else (path-and-swing) is
 * handled by the vanilla {@code MeleeAttackGoal} that the entity registers
 * permanently — this task is just the sentinel that flips {@code setTarget}
 * on and watches for the three terminal conditions (target dead, self dead,
 * timeout) before flipping it back off.
 */
public final class AttackTargetTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "attack_target";

    /** Vanilla {@code entity.getId()} of the intended victim. */
    public final int targetEntityId;

    public AttackTargetTaskRecord(String toolCallId, long deadlineGameTime, int targetEntityId) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.targetEntityId = targetEntityId;
    }
}
