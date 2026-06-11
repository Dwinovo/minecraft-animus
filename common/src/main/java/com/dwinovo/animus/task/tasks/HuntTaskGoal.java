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
 * Intent-level hunter for {@link HuntTaskRecord}: "kill N of these mob types —
 * find them, chase them, fight them, repeat." The combat analog of
 * {@link MineBlockTaskGoal}, reusing the same building blocks:
 *
 * <ul>
 *   <li>a radius scan for the nearest living target of the requested type(s);</li>
 *   <li>{@link Navigator} (terrain-modifying, follows the moving target) to close
 *       the distance — bridging, digging, and jumping as needed;</li>
 *   <li>{@link MeleeEngine} to land hits once in range.</li>
 * </ul>
 *
 * <h2>State machine (per tick)</h2>
 * <pre>
 *   SCAN   → nearest matching live mob within the current radius; auto-expand
 *            the radius up to maxRadius; none left → DONE (partial success).
 *   ENGAGE → chase (Navigator) + swing (MeleeEngine) until the target dies
 *            (count++ → SCAN), is lost (→ SCAN), or is unreachable (skip → SCAN).
 * </pre>
 *
 * <p>Drops are picked up automatically within ~1 block as the entity stands over
 * the kill; scattered drops can be swept with {@code collect_items}.
 */
public final class HuntTaskGoal extends LlmTaskGoal<HuntTaskRecord> {

    private enum Phase { SCAN, ENGAGE }

    /** Initial search radius before auto-expansion. */
    private static final int INITIAL_RADIUS = 24;
    /** Radius growth step when the current radius is exhausted. */
    private static final int RADIUS_STEP = 16;
    /** Chase speed modifier — matches the vanilla MeleeAttackGoal's 1.2. */
    private static final double CHASE_SPEED = 1.2;

    private final MeleeEngine melee;
    /** Mid-fight low-HP reflex: chews food without waiting for the LLM. */
    private final AutoEater autoEater;

    private Phase phase = Phase.SCAN;
    private int currentRadius = INITIAL_RADIUS;

    private LivingEntity target;
    private Navigator nav;

    /** Target entity ids we gave up on (unreachable) so SCAN won't re-pick them. */
    private final Set<Integer> skipped = new HashSet<>();
    private String doneReason = "done";

    public HuntTaskGoal(AnimusEntity entity) {
        super(entity, HuntTaskRecord.TOOL_NAME, HuntTaskRecord.class);
        this.melee = new MeleeEngine(entity);
        this.autoEater = new AutoEater(entity);
    }

    @Override
    protected void onStart(HuntTaskRecord r) {
        this.currentRadius = Math.min(INITIAL_RADIUS, r.maxRadius);
        this.phase = Phase.SCAN;
        this.autoEater.reset();
    }

    @Override
    protected void onTick(HuntTaskRecord r) {
        if (entity.isDeadOrDying()) {
            r.setState(TaskState.CANCELLED);
            return;
        }
        if (autoEater.tick()) {
            return;   // chewing — hold the chase/swing for this tick
        }
        switch (phase) {
            case SCAN -> tickScan(r);
            case ENGAGE -> tickEngage(r);
        }
    }

    // ---- SCAN: nearest matching live mob, auto-expanding radius ----

    private void tickScan(HuntTaskRecord r) {
        if (r.getKilled() >= r.count) {
            doneReason = "hunted all requested";
            r.setState(TaskState.SUCCESS);
            return;
        }
        LivingEntity best = nearestTarget(r);
        if (best == null) {
            if (currentRadius < r.maxRadius) {
                currentRadius = Math.min(currentRadius + RADIUS_STEP, r.maxRadius);
                return;
            }
            doneReason = r.getKilled() > 0
                    ? "only killed " + r.getKilled() + "/" + r.count + " within " + r.maxRadius + " blocks"
                    : "no " + r.label + " found within " + r.maxRadius + " blocks";
            r.setState(r.getKilled() > 0 ? TaskState.SUCCESS : TaskState.FAILED);
            return;
        }
        target = best;
        melee.reset();
        nav = new Navigator(entity, this::targetCell, CHASE_SPEED, this::inReach);
        phase = Phase.ENGAGE;
    }

    // ---- ENGAGE: chase + swing until dead / lost / unreachable ----

    private void tickEngage(HuntTaskRecord r) {
        if (target == null || target.isRemoved()) {
            stopNav();
            phase = Phase.SCAN;             // lost it — find another
            return;
        }
        if (target.isDeadOrDying()) {
            r.incrementKilled();            // counts deaths of our chosen target (any cause)
            entity.setDebugTask(r.describe());
            target = null;
            stopNav();
            phase = Phase.SCAN;
            return;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* still closing the distance */ }
            case ARRIVED -> melee.tick(target);     // in range — swing
            case FAILED -> {                         // can't route to it — abandon
                skipped.add(target.getId());
                target = null;
                stopNav();
                phase = Phase.SCAN;
            }
        }
    }

    // ---- helpers ----

    /** Live goal for the Navigator: the target's block position, or null if gone. */
    private BlockPos targetCell() {
        return (target != null && !target.isRemoved()) ? target.blockPosition() : null;
    }

    private boolean inReach() {
        return target != null && melee.inReach(target);
    }

    /** Nearest live entity of a requested type within the current radius, not skipped. */
    private LivingEntity nearestTarget(HuntTaskRecord r) {
        AABB box = entity.getBoundingBox().inflate(currentRadius);
        LivingEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity e : entity.level().getEntities(entity, box)) {
            if (e == entity || e.isRemoved()) continue;
            if (!(e instanceof LivingEntity le) || le.isDeadOrDying()) continue;
            if (!r.targets.contains(e.getType())) continue;
            if (skipped.contains(e.getId())) continue;
            double d = entity.distanceToSqr(e);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = le;
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
    protected TaskResult buildResult(HuntTaskRecord r, TaskState finalState) {
        stopNav();
        entity.getNavigation().stop();

        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("killed", r.getKilled());
        data.put("radius_searched", currentRadius);

        // Post-fight vitals (HP + anything auto-eaten) ride along on every outcome.
        return autoEater.enrich(switch (finalState) {
            case SUCCESS -> TaskResult.ok(
                    "killed " + r.getKilled() + "/" + r.count + " " + r.label + " (" + doneReason + ")",
                    data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out after killing " + r.getKilled() + "/" + r.count + " " + r.label,
                    true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "interrupted after killing " + r.getKilled() + "/" + r.count + " " + r.label
                            + " (self died or evicted)", false, true, data);
            case FAILED -> TaskResult.fail(doneReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        });
    }
}
