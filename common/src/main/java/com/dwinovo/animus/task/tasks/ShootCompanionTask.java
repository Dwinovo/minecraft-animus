package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.InputDriver;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code shoot} on the player body: destroy N targets with a bow at range, via
 * the player's NATIVE bow use (hold to charge over the draw time, release to
 * loose an arrow in the aimed direction — real arrow physics + ammo consumption)
 * instead of the Mob's RangedEngine. Ranged twin of HuntCompanionTask.
 */
public final class ShootCompanionTask implements CompanionTask {

    private enum Phase { SCAN, ENGAGE }

    private static final int INITIAL_RADIUS = 32;
    private static final int RADIUS_STEP = 16;
    private static final double APPROACH_SPEED = 1.1;
    private static final double FIRING_RANGE_SQR = 18.0 * 18.0;
    /** Vanilla bow full draw ≈ 20 ticks. */
    private static final int FULL_DRAW_TICKS = 20;

    private final AnimusPlayer player;
    private final ShootTaskRecord r;
    private final Set<Integer> skipped = new HashSet<>();

    private Phase phase = Phase.SCAN;
    private int currentRadius;
    private Entity target;
    private PlayerNav nav;
    private int drawTicks = 0;
    private String doneReason = "done";

    public ShootCompanionTask(AnimusPlayer player, ShootTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        if (!(player.getMainHandItem().getItem() instanceof ProjectileWeaponItem)) {
            doneReason = "equip a bow in your main hand first (equip_item)";
            r.setState(TaskState.FAILED);
            return;
        }
        if (player.getProjectile(player.getMainHandItem()).isEmpty()) {
            doneReason = "you have no arrows — craft or obtain arrows first";
            r.setState(TaskState.FAILED);
            return;
        }
        currentRadius = Math.min(INITIAL_RADIUS, r.maxRadius);
        phase = Phase.SCAN;
    }

    @Override
    public TaskState tick() {
        if (player.isDeadOrDying()) {
            r.setState(TaskState.CANCELLED);
            return r.getState();
        }
        switch (phase) {
            case SCAN -> tickScan();
            case ENGAGE -> tickEngage();
        }
        return r.getState();
    }

    private void tickScan() {
        if (r.getDestroyed() >= r.count) {
            doneReason = "destroyed all requested";
            r.setState(TaskState.SUCCESS);
            return;
        }
        Entity best = nearestTarget();
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
        drawTicks = 0;
        nav = new PlayerNav(player, this::targetCell, APPROACH_SPEED, this::inFiringPosition);
        phase = Phase.ENGAGE;
    }

    private void tickEngage() {
        if (target == null) {
            phase = Phase.SCAN;
            return;
        }
        if (target.isRemoved() || (target instanceof LivingEntity le && le.isDeadOrDying())) {
            r.incrementDestroyed();
            player.setDebugTask(r.describe());
            releaseIfDrawing();
            target = null;
            stopNav();
            phase = Phase.SCAN;
            return;
        }
        if (player.getProjectile(player.getMainHandItem()).isEmpty()) {
            doneReason = "ran out of arrows after " + r.getDestroyed() + "/" + r.count;
            stopNav();
            r.setState(r.getDestroyed() > 0 ? TaskState.SUCCESS : TaskState.FAILED);
            return;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* closing to firing position */ }
            case ARRIVED -> volley();
            case FAILED -> {
                skipped.add(target.getId());
                target = null;
                stopNav();
                phase = Phase.SCAN;
            }
        }
    }

    /** Charge the bow while aimed at the target, release at full draw. */
    private void volley() {
        InputDriver.lookAt(player, target.getEyePosition());
        if (!player.isUsingItem()) {
            player.gameMode.useItem(player, player.level(),
                    player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
            drawTicks = 0;
            return;
        }
        if (++drawTicks >= FULL_DRAW_TICKS) {
            player.releaseUsingItem();   // looses the arrow in the aimed direction
            drawTicks = 0;
        }
    }

    private void releaseIfDrawing() {
        if (player.isUsingItem()) player.stopUsingItem();
    }

    private BlockPos targetCell() {
        return (target != null && !target.isRemoved()) ? target.blockPosition() : null;
    }

    private boolean inFiringPosition() {
        return target != null
                && player.distanceToSqr(target) <= FIRING_RANGE_SQR
                && player.hasLineOfSight(target);
    }

    private Entity nearestTarget() {
        AABB box = player.getBoundingBox().inflate(currentRadius);
        Entity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity e : player.level().getEntities(player, box)) {
            if (e == player || e.isRemoved()) continue;
            if (!r.targets.contains(e.getType())) continue;
            if (skipped.contains(e.getId())) continue;
            if (e instanceof LivingEntity le && le.isDeadOrDying()) continue;
            double d = player.distanceToSqr(e);
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
    public TaskResult buildResult(TaskState finalState) {
        stopNav();
        releaseIfDrawing();
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("destroyed", r.getDestroyed());
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(
                    "destroyed " + r.getDestroyed() + "/" + r.count + " " + r.label + " (" + doneReason + ")", data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out after destroying " + r.getDestroyed() + "/" + r.count + " " + r.label, true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "interrupted after destroying " + r.getDestroyed() + "/" + r.count + " " + r.label, false, true, data);
            case FAILED -> TaskResult.fail(doneReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
