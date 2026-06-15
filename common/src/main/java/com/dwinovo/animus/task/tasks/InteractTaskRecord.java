package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;
import net.minecraft.core.BlockPos;

/**
 * Typed descriptor for the {@code interact} tool — the lowest-level native
 * interaction: aim at a target and press one mouse button.
 * <ul>
 *   <li>{@link Button#USE} (right-click) on a block = activate it (lever, button,
 *       door, bed, crafting table, modded machine); on an entity = trade / breed
 *       / mount / name with the held item.</li>
 *   <li>{@link Button#ATTACK} (left-click) on a block = break it (native timed);
 *       on an entity = hit it once.</li>
 * </ul>
 * Exactly one target is supplied — a block ({@code x,y,z}) or an entity
 * ({@code entity_id}).
 */
public final class InteractTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "interact";

    public enum Button { USE, ATTACK }
    public enum TargetKind { BLOCK, ENTITY }

    public final Button button;
    public final TargetKind targetKind;
    public final BlockPos block;   // non-null iff BLOCK
    public final int entityId;     // meaningful iff ENTITY

    public InteractTaskRecord(String toolCallId, long deadlineGameTime,
                              Button button, BlockPos block, Integer entityId) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.button = button;
        boolean hasBlock = block != null;
        boolean hasEntity = entityId != null;
        if (hasBlock == hasEntity) {
            throw new IllegalArgumentException(
                    "interact needs exactly one target: either x+y+z (a block) or "
                    + "entity_id (an entity), not both and not neither.");
        }
        this.targetKind = hasBlock ? TargetKind.BLOCK : TargetKind.ENTITY;
        this.block = hasBlock ? block.immutable() : null;
        this.entityId = hasEntity ? entityId : -1;
    }

    @Override
    public String describe() {
        String verb = button == Button.USE ? "use" : "attack";
        return TOOL_NAME + " " + verb + " "
                + (targetKind == TargetKind.BLOCK
                        ? block.getX() + "," + block.getY() + "," + block.getZ()
                        : "entity#" + entityId);
    }
}
