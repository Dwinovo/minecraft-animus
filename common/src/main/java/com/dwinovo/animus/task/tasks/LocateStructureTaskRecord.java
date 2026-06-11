package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;

/**
 * Typed task descriptor for {@code locate_structure}: "where is the nearest
 * X?" — X being a structure id ({@code minecraft:fortress}) or a structure
 * tag ({@code #minecraft:village}). Resolution happens server-side in the
 * goal, where the registry lives.
 */
public final class LocateStructureTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "locate_structure";

    /** Raw structure argument as the LLM gave it: an id, or a {@code #}-prefixed tag. */
    public final String structure;

    public LocateStructureTaskRecord(String toolCallId, long deadlineGameTime, String structure) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.structure = structure;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + structure;
    }
}
