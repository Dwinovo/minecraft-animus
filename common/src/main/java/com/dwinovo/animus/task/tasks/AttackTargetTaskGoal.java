package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.exec.Navigator;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for {@link AttackTargetTaskRecord}. Chases the target with the shared
 * terrain-modifying {@link Navigator} (so it can bridge gaps, mine through cover,
 * and parkour to reach a target the vanilla {@code MeleeAttackGoal}'s plain
 * navigation couldn't) and lands hits with the shared {@link MeleeEngine} once in
 * range.
 *
 * <p>The Navigator follows a moving goal — the target's live block position — and
 * its {@code reached} predicate is "within melee range". Each tick the entity is
 * either still closing (RUNNING) or in range (ARRIVED → swing). We deliberately
 * do <em>not</em> call {@code setTarget}, so the permanently-registered vanilla
 * {@code MeleeAttackGoal} stays idle and doesn't fight our Navigator for the
 * MoveControl.
 *
 * <h2>Terminal conditions</h2>
 * <ul>
 *   <li>{@code SUCCESS} — target dead or dying.</li>
 *   <li>{@code FAILED} — target despawned/unloaded, or genuinely unreachable.</li>
 *   <li>{@code TIMEOUT} — base-class deadline (under-DPS on a tanky target).</li>
 *   <li>{@code CANCELLED} — our own entity died / selector eviction.</li>
 * </ul>
 */
public final class AttackTargetTaskGoal extends LlmTaskGoal<AttackTargetTaskRecord> {

    /** Chase speed modifier — matches the vanilla MeleeAttackGoal's 1.2. */
    private static final double CHASE_SPEED = 1.2;

    private LivingEntity target;
    private Navigator nav;
    private MeleeEngine melee;

    public AttackTargetTaskGoal(AnimusEntity entity) {
        super(entity, AttackTargetTaskRecord.TOOL_NAME, AttackTargetTaskRecord.class);
    }

    @Override
    protected void onStart(AttackTargetTaskRecord r) {
        Entity raw = entity.level().getEntity(r.targetEntityId);
        if (!(raw instanceof LivingEntity living) || living == entity) {
            // Unknown id, non-living, removed, or (belt-and-braces) ourselves.
            r.setState(TaskState.FAILED);
            return;
        }
        if (living.isDeadOrDying()) {
            // Died before the record reached the head of the queue — the LLM's
            // intended outcome already happened.
            r.setState(TaskState.SUCCESS);
            return;
        }
        this.target = living;
        this.melee = new MeleeEngine(entity);
        this.nav = new Navigator(entity, this::targetCell, CHASE_SPEED, this::inReach);
    }

    /** Live goal for the Navigator: the target's block position, or null if gone. */
    private BlockPos targetCell() {
        return (target != null && !target.isRemoved()) ? target.blockPosition() : null;
    }

    private boolean inReach() {
        return target != null && melee.inReach(target);
    }

    @Override
    protected void onTick(AttackTargetTaskRecord r) {
        if (entity.isDeadOrDying()) {
            r.setState(TaskState.CANCELLED);
            return;
        }
        if (target == null || target.isRemoved()) {
            r.setState(TaskState.FAILED);
            return;
        }
        if (target.isDeadOrDying()) {
            r.setState(TaskState.SUCCESS);
            return;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* still closing the distance */ }
            case ARRIVED -> {                      // in melee range — swing
                if (melee.tick(target)) {
                    r.setState(TaskState.SUCCESS);
                }
            }
            case FAILED -> r.setState(TaskState.FAILED);
        }
    }

    @Override
    protected TaskResult buildResult(AttackTargetTaskRecord r, TaskState finalState) {
        if (nav != null) nav.stop();
        entity.getNavigation().stop();

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
            case FAILED -> TaskResult.fail(
                    "couldn't reach or hold the target (despawned, unloaded, or unreachable)", data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
