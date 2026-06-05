package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * Typed task descriptor for the intent-level {@code craft} tool: "make
 * {@code count} of this item — find the recipe, gather the grid, walk to / place
 * a crafting table if it needs one, and craft it." The goal
 * ({@link CraftTaskGoal}) owns the whole flow; the LLM only declares
 * <em>what</em> and <em>how many</em>.
 *
 * <p>{@code count} is the desired number of output <em>items</em>, not crafting
 * operations. Batch recipes (planks → 4, sticks → 4) may overshoot on the final
 * craft; the real produced total is reported back.
 */
public final class CraftTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "craft";

    /** The item to produce. */
    public final Item item;
    /** Desired number of output items before reporting success. */
    public final int count;
    /** How far to look for an existing crafting table (3×3 recipes only). */
    public final int tableSearchRadius;
    /** Human-readable label for messages / debug overlay (e.g. "wooden_pickaxe"). */
    public final String label;

    /** Live progress (output items produced so far) — drives the debug overlay. */
    private int produced = 0;

    public CraftTaskRecord(String toolCallId, long deadlineGameTime,
                           Item item, int count, int tableSearchRadius, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.item = item;
        this.count = count;
        this.tableSearchRadius = tableSearchRadius;
        this.label = label;
    }

    public int getProduced() {
        return produced;
    }

    public void addProduced(int n) {
        this.produced += n;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + label + " " + produced + "/" + count;
    }
}
