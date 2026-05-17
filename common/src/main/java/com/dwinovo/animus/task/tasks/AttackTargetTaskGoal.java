package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for {@link AttackTargetTaskRecord}. Acts purely as a lifecycle
 * sentinel: it flips {@code entity.setTarget(...)} on at {@link #onStart} so
 * the entity's permanently-registered vanilla {@code MeleeAttackGoal} kicks
 * in (path-find, swing, doHurtTarget, knockback, attack cooldown — all
 * handled by vanilla), then watches each tick for one of:
 * <ul>
 *   <li>{@link TaskState#SUCCESS} — target is dead or has been removed from
 *       the level (the kill landed).</li>
 *   <li>{@link TaskState#FAILED} — target id no longer resolves but isn't
 *       known to be dead (despawn / unload / out-of-range invalidation).</li>
 *   <li>{@link TaskState#TIMEOUT} — base-class deadline hit; the LLM picked
 *       a target it can't realistically kill in time, surface that fact.</li>
 *   <li>{@link TaskState#CANCELLED} — selector evicted us, e.g. our own
 *       entity died mid-fight.</li>
 * </ul>
 *
 * <p>{@link #stop} always clears {@code setTarget(null)} so the vanilla
 * MeleeAttackGoal's {@code canContinueToUse} returns false and the entity
 * stops mid-charge cleanly.
 *
 * <h2>Flag interaction with MeleeAttackGoal</h2>
 * The vanilla {@code MeleeAttackGoal} owns {@code MOVE + LOOK} flags;
 * {@code LlmTaskGoal} declares no flags (see its javadoc on "serial
 * execution"), so this sentinel and the attack goal run side-by-side in
 * the same GoalSelector tick without a mutex conflict.
 */
public final class AttackTargetTaskGoal extends LlmTaskGoal<AttackTargetTaskRecord> {

    /** Resolved at {@link #onStart}; nulled in {@link #stop}. */
    private LivingEntity target;

    public AttackTargetTaskGoal(AnimusEntity entity) {
        super(entity, AttackTargetTaskRecord.TOOL_NAME, AttackTargetTaskRecord.class);
    }

    @Override
    protected void onStart(AttackTargetTaskRecord r) {
        Entity raw = entity.level().getEntity(r.targetEntityId);
        if (!(raw instanceof LivingEntity living)) {
            // Unknown id, non-living (item entity, projectile, etc.), or already removed.
            r.setState(TaskState.FAILED);
            return;
        }
        if (living == entity) {
            // Belt-and-braces: tool layer should reject this, but never let
            // the entity target itself — vanilla MeleeAttackGoal would happily
            // try and lock the entity into a self-pathing loop.
            r.setState(TaskState.FAILED);
            return;
        }
        if (living.isDeadOrDying()) {
            // Target was alive when the LLM picked it but died before the
            // record reached the head of the queue — treat as success, the
            // outcome the LLM wanted has already happened.
            r.setState(TaskState.SUCCESS);
            return;
        }
        this.target = living;
        entity.setTarget(living);
    }

    @Override
    protected void onTick(AttackTargetTaskRecord r) {
        if (entity.isDeadOrDying()) {
            // Self-death — selector will evict us in a tick or two; mark
            // CANCELLED explicitly so buildResult tells the right story.
            r.setState(TaskState.CANCELLED);
            return;
        }
        if (target == null || target.isRemoved()) {
            // Target despawned / left the loaded area without dying. The
            // permanent MeleeAttackGoal would just stand idle from here.
            r.setState(TaskState.FAILED);
            return;
        }
        if (target.isDeadOrDying()) {
            r.setState(TaskState.SUCCESS);
        }
    }

    @Override
    protected TaskResult buildResult(AttackTargetTaskRecord r, TaskState finalState) {
        // Always release the target — even on TIMEOUT/CANCELLED the entity
        // should stop chasing when the task ends.
        entity.setTarget(null);
        Map<String, Object> data = new HashMap<>();
        data.put("target_entity_id", r.targetEntityId);
        data.put("target_alive", target != null && !target.isRemoved() && !target.isDeadOrDying());
        if (target != null) {
            data.put("target_type", target.getType().getDescriptionId());
            data.put("target_remaining_hp", target.getHealth());
        }
        this.target = null;

        return switch (finalState) {
            case SUCCESS -> TaskResult.ok("target eliminated", data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out before target died", true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "attack interrupted (self died or evicted)", false, true, data);
            case FAILED -> TaskResult.fail("target lost (despawned, unloaded, or invalid id)", data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
