package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * Typed task descriptor for {@code eat_item}: consume a food from the entity's
 * inventory to heal itself. Unlike {@code use_item} (which routes through a fake
 * player and would feed the fake player, not the Animus), this applies directly
 * to the Animus. The goal ({@link EatItemTaskGoal}) eats it as a real, timed
 * process — chewing particles + sound over the food's consume duration — and
 * only heals / applies the food's effects once eating completes.
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
