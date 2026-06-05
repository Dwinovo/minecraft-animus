package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * Typed task descriptor for {@code load_furnace}: "find/place a furnace, walk to
 * it, and load {@code count} of {@code input} plus {@code fuel} into it." The
 * goal ({@link LoadFurnaceTaskGoal}) returns as soon as loading succeeds — the
 * world furnace then smelts on its own (async), to be polled with
 * {@code check_furnace} and emptied with {@code collect_furnace}.
 */
public final class LoadFurnaceTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "load_furnace";

    /** Item to smelt (goes in the input slot). */
    public final Item input;
    /** How many input items to load (capped to the input slot's stack size). */
    public final int count;
    /** Fuel item to load; the goal computes how many are needed for {@code count}. */
    public final Item fuel;
    /** How far to look for an existing furnace before placing one we carry. */
    public final int searchRadius;
    /** Human-readable input label for messages / debug overlay (e.g. "raw_iron"). */
    public final String label;

    public LoadFurnaceTaskRecord(String toolCallId, long deadlineGameTime,
                                 Item input, int count, Item fuel, int searchRadius, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.input = input;
        this.count = count;
        this.fuel = fuel;
        this.searchRadius = searchRadius;
        this.label = label;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + count + "x " + label;
    }
}
