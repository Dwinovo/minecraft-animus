package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.exec.Navigator;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Intent-level archer for {@link ShootTaskRecord}: "destroy N of these entity
 * types with a bow." The ranged sibling of {@link HuntTaskGoal} — it reuses the
 * {@link Navigator} to close to firing position and the {@link RangedEngine} to
 * loose arrows.
 *
 * <h2>State machine (per tick)</h2>
 * <pre>
 *   SCAN   → nearest matching entity within the current radius; auto-expand the
 *            radius up to maxRadius; none left → DONE (partial success).
 *   ENGAGE → close to within bow range + line of sight (Navigator), then fire
 *            (RangedEngine) until the target is down (count++ → SCAN), lost
 *            (→ SCAN), unreachable (skip → SCAN), or out of arrows (DONE).
 * </pre>
 *
 * <p>Targets may be non-living (e.g. {@code end_crystal}), which is the whole
 * point — those must be destroyed at range.
 */
public final class ShootTaskGoal extends LlmTaskGoal<ShootTaskRecord> {

    private enum Phase { SCAN, ENGAGE }

    private static final int INITIAL_RADIUS = 32;
    private static final int RADIUS_STEP = 16;
    /** Approach speed to firing position. */
    private static final double APPROACH_SPEED = 1.1;
    /** Within this range² AND with line of sight, we can loose arrows (~18 blocks). */
    private static final double FIRING_RANGE_SQR = 18.0 * 18.0;

    private final RangedEngine ranged;

    private Phase phase = Phase.SCAN;
    private int currentRadius = INITIAL_RADIUS;

    private Entity target;
    private Navigator nav;

    private final Set<Integer> skipped = new HashSet<>();
    private String doneReason = "done";

    public ShootTaskGoal(AnimusEntity entity) {
        super(entity, ShootTaskRecord.TOOL_NAME, ShootTaskRecord.class);
        this.ranged = new RangedEngine(entity);
    }

    @Override
    protected void onStart(ShootTaskRecord r) {
        // Gate up front with actionable guidance (the model's instruction manual):
        // shooting needs a bow held and arrows carried.
        if (!ranged.hasBow()) {
            doneReason = "equip a bow in your main hand first (equip_item)";
            r.setState(TaskState.FAILED);
            return;
        }
        if (!ranged.hasArrows()) {
            doneReason = "you have no arrows — craft or obtain arrows first";
            r.setState(TaskState.FAILED);
            return;
        }
        this.currentRadius = Math.min(INITIAL_RADIUS, r.maxRadius);
        this.phase = Phase.SCAN;
    }

    @Override
    protected void onTick(ShootTaskRecord r) {
        if (entity.isDeadOrDying()) {
            r.setState(TaskState.CANCELLED);
            return;
        }
        switch (phase) {
            case SCAN -> tickScan(r);
            case ENGAGE -> tickEngage(r);
        }
    }

    private void tickScan(ShootTaskRecord r) {
        if (r.getDestroyed() >= r.count) {
            doneReason = "destroyed all requested";
            r.setState(TaskState.SUCCESS);
            return;
        }
        Entity best = nearestTarget(r);
        if (best == null) {
            if (currentRadius < r.maxRadius) {
                currentRadius = Math.min(currentRadius + RADIUS_STEP, r.maxRadius);
                return;
            }
            doneReason = r.getDestroyed() > 0
                    ? "only destroyed " + r.getDestroyed() + "/" + r.count + " within " + r.maxRadius + " blocks"
                    : "no " + r.label + " found within " + r.maxRadius + " blocks";
            r.setState(r.getDestroyed() > 0 ? TaskState.SUCCESS : TaskState.FAILED);
            return;
        }
        target = best;
        ranged.reset();
        nav = new Navigator(entity, this::targetCell, APPROACH_SPEED, this::inFiringPosition);
        phase = Phase.ENGAGE;
    }

    private void tickEngage(ShootTaskRecord r) {
        if (target == null) {
            phase = Phase.SCAN;
            return;
        }
        if (target.isRemoved()
                || (target instanceof LivingEntity le && le.isDeadOrDying())) {
            r.incrementDestroyed();           // our chosen target is down
            entity.setDebugTask(r.describe());
            target = null;
            stopNav();
            phase = Phase.SCAN;
            return;
        }
        if (!ranged.hasArrows()) {
            doneReason = "ran out of arrows after " + r.getDestroyed() + "/" + r.count;
            stopNav();
            r.setState(r.getDestroyed() > 0 ? TaskState.SUCCESS : TaskState.FAILED);
            return;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* closing to firing position */ }
            case ARRIVED -> ranged.tick(target);    // in range + LOS — loose arrows
            case FAILED -> {                          // can't get a shot — abandon
                skipped.add(target.getId());
                target = null;
                stopNav();
                phase = Phase.SCAN;
            }
        }
    }

    // ---- helpers ----

    private BlockPos targetCell() {
        return (target != null && !target.isRemoved()) ? target.blockPosition() : null;
    }

    /** Reached = within bow range AND with a clear line of sight to fire. */
    private boolean inFiringPosition() {
        return target != null
                && entity.distanceToSqr(target) <= FIRING_RANGE_SQR
                && entity.hasLineOfSight(target);
    }

    /** Nearest entity of a requested type within the current radius, not skipped. */
    private Entity nearestTarget(ShootTaskRecord r) {
        AABB box = entity.getBoundingBox().inflate(currentRadius);
        Entity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity e : entity.level().getEntities(entity, box)) {
            if (e == entity || e.isRemoved()) continue;
            if (!r.targets.contains(e.getType())) continue;
            if (skipped.contains(e.getId())) continue;
            if (e instanceof LivingEntity le && le.isDeadOrDying()) continue;
            double d = entity.distanceToSqr(e);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = e;
            }
        }
        return best;
    }

    private void stopNav() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
    }

    @Override
    protected TaskResult buildResult(ShootTaskRecord r, TaskState finalState) {
        stopNav();
        entity.getNavigation().stop();

        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("destroyed", r.getDestroyed());
        data.put("radius_searched", currentRadius);

        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(
                    "destroyed " + r.getDestroyed() + "/" + r.count + " " + r.label + " (" + doneReason + ")",
                    data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out after destroying " + r.getDestroyed() + "/" + r.count + " " + r.label,
                    true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "interrupted after destroying " + r.getDestroyed() + "/" + r.count + " " + r.label
                            + " (self died or evicted)", false, true, data);
            case FAILED -> TaskResult.fail(doneReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
