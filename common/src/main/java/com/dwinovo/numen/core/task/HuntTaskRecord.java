package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.task.TaskRecord;
import net.minecraft.world.entity.EntityType;

import java.util.Set;

/**
 * Typed task descriptor for the intent-level {@code hunt} tool: "kill
 * {@code count} of these entity types, find/chase/fight them yourself". The
 * combat analog of {@link MineBlockTaskRecord} — the goal ({@link HuntTaskGoal})
 * owns the loop: scan for the nearest matching mob, chase it with the
 * terrain-modifying pathfinder, melee it to death, repeat until the count is met
 * or none remain within the radius. Drops are auto-collected within pickup range.
 *
 * <p>The LLM declares <em>what</em> and <em>how many</em>; no coordinates, no
 * target ids.
 */
public final class HuntTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "hunt";

    /** Entity types to hunt (e.g. zombie, or blaze for nether progression). */
    public final Set<EntityType<?>> targets;
    /** How many to kill before reporting success. */
    public final int count;
    /** Max spherical search radius (the goal auto-expands up to this). */
    public final int maxRadius;
    /** Human-readable target label for messages / debug overlay (e.g. "zombie"). */
    public final String label;

    /** Live progress, updated by the goal as targets die — drives the debug overlay. */
    private int killed = 0;

    public HuntTaskRecord(String toolCallId, long deadlineGameTime,
                          Set<EntityType<?>> targets, int count, int maxRadius, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.targets = Set.copyOf(targets);
        this.count = count;
        this.maxRadius = maxRadius;
        this.label = label;
    }

    public int getKilled() {
        return killed;
    }

    public void incrementKilled() {
        this.killed++;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + label + " " + killed + "/" + count;
    }
}
