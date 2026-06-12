package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * Typed task descriptor for {@code use_item}: right-click an item, optionally on
 * a target block. The goal ({@link UseItemTaskGoal}) performs it through the
 * loader's shared fake player ({@link FakePlayerUse}) so real vanilla item logic
 * runs (flint&steel ignites, ender eye fills an end-portal frame / is thrown,
 * bucket/bonemeal, …).
 *
 * <p>{@code target == null} → use in the air (throwables, etc.); otherwise →
 * use on the block at {@code target} (the goal walks within reach first).
 */
public final class UseItemTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "use_item";

    public final Item item;
    /** Block to use the item on, or {@code null} for an in-air use. */
    public final BlockPos target;
    public final String label;
    /** 0 = single click; &gt;0 = hold right-click this many ticks, then release. */
    public final int holdTicks;

    public UseItemTaskRecord(String toolCallId, long deadlineGameTime,
                             Item item, BlockPos target, String label, int holdTicks) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.item = item;
        this.target = target;
        this.label = label;
        this.holdTicks = holdTicks;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + label + (target == null ? " (air)"
                : " @ " + target.getX() + "," + target.getY() + "," + target.getZ());
    }
}
