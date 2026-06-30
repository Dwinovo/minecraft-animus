package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.task.TaskRecord;
import net.minecraft.world.item.Item;

import java.util.Set;

/**
 * Typed task descriptor for the {@code collect_items} tool: "walk around and
 * pick up dropped items nearby". The goal ({@link CollectItemsTaskGoal}) scans
 * for {@code ItemEntity}s within the radius, walks to each with the pathfinder
 * (the entity auto-absorbs items it gets close to), and repeats until none
 * remain. An optional {@link #filter} restricts to specific item types; empty
 * means collect everything.
 */
public final class CollectItemsTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "collect_items";

    /** Item types to collect; empty = collect every dropped item. */
    public final Set<Item> filter;
    /** Search radius in blocks. */
    public final int radius;
    /** Human-readable label for messages (e.g. "all items" or "diamond"). */
    public final String label;

    /** Live progress, updated by the goal as items are absorbed. */
    private int collected = 0;

    public CollectItemsTaskRecord(String toolCallId, long deadlineGameTime,
                                  Set<Item> filter, int radius, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.filter = Set.copyOf(filter);
        this.radius = radius;
        this.label = label;
    }

    public int getCollected() {
        return collected;
    }

    public void incrementCollected() {
        this.collected++;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + label + " x" + collected;
    }
}
