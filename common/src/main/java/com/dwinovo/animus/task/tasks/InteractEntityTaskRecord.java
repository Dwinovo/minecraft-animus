package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;

/**
 * Typed descriptor for {@code interact_entity} — the entity-aimed half of the native
 * crosshair interaction (the ENTITY column of vanilla's {@code startAttack}/{@code startUseItem}).
 * Entities are the only MOVING interaction target, so this is the one that auto-paths AND
 * follows the live entity (by id from {@code scan_nearby_entities}) before pressing a button:
 * <ul>
 *   <li>{@link Button#LEFT} (attack): hit it — tap = one cooldown-gated hit, hold = keep
 *       hitting until it dies (= the old {@code hunt}).</li>
 *   <li>{@link Button#RIGHT} (use): interact — trade / breed / mount / shear / name with the
 *       held item; hold = a modded entity needing continuous right-click.</li>
 * </ul>
 * The hit only lands when the native raytrace actually REACHES the entity (a wall in between
 * blocks it — we re-position rather than hit through it). {@code holdTicks}: 0 = tap, &gt;0 =
 * hold N ticks, -1 = hold until done (dead / self-complete) or timeout.
 */
public final class InteractEntityTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "interact_entity";

    public enum Button { LEFT, RIGHT }   // left = attack, right = use

    public final Button button;
    public final int entityId;
    public final int holdTicks;

    public InteractEntityTaskRecord(String toolCallId, long deadlineGameTime,
                                    Button button, int entityId, int holdTicks) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.button = button;
        this.entityId = entityId;
        this.holdTicks = holdTicks;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + (button == Button.LEFT ? "left" : "right")
                + " entity#" + entityId + (holdTicks != 0 ? " hold=" + holdTicks : "");
    }
}
