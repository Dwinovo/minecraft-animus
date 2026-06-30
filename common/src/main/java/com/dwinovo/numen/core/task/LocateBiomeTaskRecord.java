package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.task.TaskRecord;

/**
 * Typed task descriptor for {@code locate_biome}: "where is the nearest X?" —
 * X being a biome id ({@code minecraft:warped_forest}) or a biome tag
 * ({@code #minecraft:is_forest}). Resolution happens server-side in the goal,
 * where the registry lives.
 */
public final class LocateBiomeTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "locate_biome";

    /** Raw biome argument as the LLM gave it: an id, or a {@code #}-prefixed tag. */
    public final String biome;

    public LocateBiomeTaskRecord(String toolCallId, long deadlineGameTime, String biome) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.biome = biome;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + biome;
    }
}
