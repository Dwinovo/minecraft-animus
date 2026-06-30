package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * Typed task descriptor for {@code eat_item}: consume a food / drink from the
 * companion's inventory. {@link EatCompanionTask} eats it natively (the player
 * body's own held-use path), so hunger + saturation + consume effects apply
 * exactly as they do for a real player when the chew completes.
 */
public final class EatItemTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "eat_item";

    /** The food item to eat (one is consumed on completion). */
    public final Item item;
    /** Human-readable label for messages / debug overlay (e.g. "golden_apple"). */
    public final String label;

    public EatItemTaskRecord(String toolCallId, long deadlineGameTime, Item item, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.item = item;
        this.label = label;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + label;
    }
}
