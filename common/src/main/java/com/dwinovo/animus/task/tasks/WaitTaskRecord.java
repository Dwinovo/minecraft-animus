package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;

/**
 * Typed task descriptor for {@code wait}: "stand by for {@code seconds}". The
 * deliberate idle the agent otherwise lacks — without it the model fills every
 * dead moment (smelting, nightfall, owner AFK) with busywork tool calls.
 */
public final class WaitTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "wait";

    /** How long to wait, in seconds of game time. */
    public final int seconds;
    /** Optional reason, shown on the debug overlay (e.g. "waiting for iron to smelt"). */
    public final String reason;

    public WaitTaskRecord(String toolCallId, long deadlineGameTime, int seconds, String reason) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.seconds = seconds;
        this.reason = reason;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + seconds + "s" + (reason.isEmpty() ? "" : " (" + reason + ")");
    }
}
