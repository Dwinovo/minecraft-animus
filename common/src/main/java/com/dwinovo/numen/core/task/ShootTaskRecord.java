package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.task.TaskRecord;
import net.minecraft.world.entity.EntityType;

import java.util.Set;

/**
 * Typed task descriptor for the intent-level {@code shoot} tool: "destroy
 * {@code count} of these entity types with a bow". The ranged sibling of
 * {@link HuntTaskRecord} — the goal ({@link ShootTaskGoal}) scans for the
 * nearest matching entity, closes to within bow range + line of sight with the
 * pathfinder, and looses arrows until it's down, repeating until the count is
 * met or none remain.
 *
 * <p>Unlike {@code hunt}, targets need not be living — an {@code end_crystal} is
 * a non-living entity that <em>must</em> be destroyed at range, which is exactly
 * why this tool exists on the road to the dragon.
 */
public final class ShootTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "shoot";

    /** Entity types to destroy (e.g. end_crystal, blaze, or any mob). */
    public final Set<EntityType<?>> targets;
    /** How many to destroy before reporting success. */
    public final int count;
    /** Max spherical search radius (the goal auto-expands up to this). */
    public final int maxRadius;
    /** Human-readable target label for messages / debug overlay. */
    public final String label;

    private int destroyed = 0;

    public ShootTaskRecord(String toolCallId, long deadlineGameTime,
                           Set<EntityType<?>> targets, int count, int maxRadius, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.targets = Set.copyOf(targets);
        this.count = count;
        this.maxRadius = maxRadius;
        this.label = label;
    }

    public int getDestroyed() {
        return destroyed;
    }

    public void incrementDestroyed() {
        this.destroyed++;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + label + " " + destroyed + "/" + count;
    }
}
