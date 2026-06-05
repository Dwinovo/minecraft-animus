package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.exec.Navigator;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Intent-level item sweeper for {@link CollectItemsTaskRecord}: "pick up the
 * dropped items around here." The entity already auto-absorbs items within ~1
 * block ({@code setCanPickUpLoot}); this goal actively walks it to each
 * scattered drop with the pathfinder so nothing is left behind after a mine or a
 * hunt.
 *
 * <h2>State machine (per tick)</h2>
 * <pre>
 *   SCAN     → nearest matching ItemEntity within the radius; none → DONE.
 *   APPROACH → Navigator toward it until it's absorbed (collected++) or we
 *              reach the spot without picking it up (skip), then re-SCAN.
 * </pre>
 */
public final class CollectItemsTaskGoal extends LlmTaskGoal<CollectItemsTaskRecord> {

    private enum Phase { SCAN, APPROACH }

    private static final double WALK_SPEED = 1.0;
    /** Close enough that vanilla auto-pickup should have absorbed the item (≈1.2 blocks). */
    private static final double PICKUP_REACH_SQR = 1.5;

    private Phase phase = Phase.SCAN;
    private ItemEntity target;
    private Navigator nav;

    /** Item-entity ids we reached but couldn't absorb, so SCAN won't loop on them. */
    private final Set<Integer> skipped = new HashSet<>();

    public CollectItemsTaskGoal(AnimusEntity entity) {
        super(entity, CollectItemsTaskRecord.TOOL_NAME, CollectItemsTaskRecord.class);
    }

    @Override
    protected void onStart(CollectItemsTaskRecord r) {
        this.phase = Phase.SCAN;
    }

    @Override
    protected void onTick(CollectItemsTaskRecord r) {
        if (entity.isDeadOrDying()) {
            r.setState(TaskState.CANCELLED);
            return;
        }
        switch (phase) {
            case SCAN -> tickScan(r);
            case APPROACH -> tickApproach(r);
        }
    }

    private void tickScan(CollectItemsTaskRecord r) {
        ItemEntity best = nearestItem(r);
        if (best == null) {
            // Nothing left within radius — done. Success even at 0 (the LLM asked
            // us to sweep; "nothing here" is a valid, useful answer).
            r.setState(TaskState.SUCCESS);
            return;
        }
        target = best;
        nav = new Navigator(entity, this::targetCell, WALK_SPEED, this::picked);
        phase = Phase.APPROACH;
    }

    private void tickApproach(CollectItemsTaskRecord r) {
        if (target == null || target.isRemoved()) {
            // Absorbed (by us or otherwise) — count it if it was ours to get.
            if (target != null) {
                r.incrementCollected();
                entity.setDebugTask(r.describe());
            }
            stopNav();
            phase = Phase.SCAN;
            return;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* walking to it */ }
            case ARRIVED -> {
                // Reached the spot. If it's now absorbed, the removed-branch above
                // counts it next tick; otherwise we can't pick it up — skip it.
                if (!target.isRemoved()) {
                    skipped.add(target.getId());
                    target = null;
                    stopNav();
                    phase = Phase.SCAN;
                }
            }
            case FAILED -> {                 // can't route to it — abandon
                if (target != null) skipped.add(target.getId());
                target = null;
                stopNav();
                phase = Phase.SCAN;
            }
        }
    }

    private BlockPos targetCell() {
        return (target != null && !target.isRemoved()) ? target.blockPosition() : null;
    }

    /** Reached = absorbed, or close enough that auto-pickup should have fired. */
    private boolean picked() {
        return target == null || target.isRemoved()
                || entity.distanceToSqr(target) <= PICKUP_REACH_SQR;
    }

    private ItemEntity nearestItem(CollectItemsTaskRecord r) {
        AABB box = entity.getBoundingBox().inflate(r.radius);
        ItemEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity e : entity.level().getEntities(entity, box)) {
            if (!(e instanceof ItemEntity ie) || ie.isRemoved()) continue;
            if (skipped.contains(ie.getId())) continue;
            if (!r.filter.isEmpty() && !r.filter.contains(ie.getItem().getItem())) continue;
            double d = entity.distanceToSqr(ie);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = ie;
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
    protected TaskResult buildResult(CollectItemsTaskRecord r, TaskState finalState) {
        stopNav();
        entity.getNavigation().stop();

        Map<String, Object> data = new HashMap<>();
        data.put("label", r.label);
        data.put("collected", r.getCollected());
        data.put("radius", r.radius);

        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(
                    "collected " + r.getCollected() + " " + r.label, data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out after collecting " + r.getCollected() + " " + r.label,
                    true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "interrupted after collecting " + r.getCollected() + " " + r.label,
                    false, true, data);
            case FAILED -> TaskResult.fail("could not collect items", data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
