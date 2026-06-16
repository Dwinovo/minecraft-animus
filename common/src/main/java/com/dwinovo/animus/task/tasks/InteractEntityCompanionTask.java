package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.InputDriver;
import com.dwinovo.animus.pathing.exec.Interaction;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code interact_entity} on the player body — the entity-aimed native interaction. Auto-paths
 * and FOLLOWS the live entity (the only moving interaction target), then aims at it and presses
 * the requested mouse button — but only when the native raytrace actually REACHES the entity
 * (a wall in between blocks it, and we re-position instead of hitting through it, which diverges
 * from Carpet's "hit whatever the ray returns" — deliberate: this tool means "act on THIS
 * entity", not "grief the wall it hid behind"). attack+hold = keep hitting until dead (= hunt).
 */
public final class InteractEntityCompanionTask implements CompanionTask {

    private static final double REACH = 3.0;            // vanilla entity interaction range
    private static final double REACH_SQR = REACH * REACH;
    private static final double WALK_SPEED = 1.0;

    private final AnimusPlayer player;
    private final InteractEntityTaskRecord r;

    private Entity entity;
    private PlayerNav nav;
    private Interaction interaction;
    private long holdUntil = -1;
    private boolean acted = false;     // landed at least one press (death then = success, not failure)
    private String doneReason = "done";

    public InteractEntityCompanionTask(AnimusPlayer player, InteractEntityTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        entity = ((ServerLevel) player.level()).getEntity(r.entityId);
        if (entity == null || !entity.isAlive()) {
            doneReason = "no entity with id " + r.entityId + " nearby (it may have despawned or moved out of range)";
            r.setState(TaskState.FAILED);
            return;
        }
        nav = new PlayerNav(player, () -> entity.blockPosition(), WALK_SPEED, this::withinReach);
    }

    @Override
    public TaskState tick() {
        if (entity == null || !entity.isAlive()) {
            // Death is success for an attack that landed (the old hunt's contract); otherwise the
            // target slipped away before we could touch it.
            doneReason = acted
                    ? (r.button == InteractEntityTaskRecord.Button.LEFT ? "defeated " + name() : "done with " + name())
                    : "the target entity is gone before I could reach it";
            return acted ? TaskState.SUCCESS : TaskState.FAILED;
        }

        // Follow until within reach.
        if (!withinReach()) {
            if (nav == null) return TaskState.FAILED;
            return switch (nav.tick()) {
                case RUNNING, ARRIVED -> TaskState.RUNNING;
                case FAILED -> {
                    doneReason = "can't reach " + name() + ": " + nav.failReason();
                    yield TaskState.FAILED;
                }
            };
        }

        // In reach: aim at the entity and confirm the crosshair actually reaches IT (no wall).
        InputDriver.lookAt(player, entity.getEyePosition());
        HitResult hit = Interaction.nativeRaytrace(player, REACH);
        boolean onTarget = hit.getType() == HitResult.Type.ENTITY
                && ((EntityHitResult) hit).getEntity() == entity;
        if (!onTarget) {
            // A wall (or another entity) is in the way — re-position rather than hit through it.
            if (nav != null) nav.tick();
            return TaskState.RUNNING;
        }

        if (interaction == null) {
            interaction = Interaction.forHit(player, hit, button(), r.holdTicks);
            if (r.holdTicks > 0) {
                holdUntil = player.level().getGameTime() + r.holdTicks;
            }
        }
        acted = true;

        if (holdUntil >= 0 && player.level().getGameTime() >= holdUntil) {
            interaction.stop();
            doneReason = describeDone();
            return TaskState.SUCCESS;
        }
        return switch (interaction.tick()) {
            case DONE -> {
                doneReason = describeDone();
                yield TaskState.SUCCESS;
            }
            case FAILED -> {
                doneReason = interaction.failReason();
                yield TaskState.FAILED;
            }
            case RUNNING -> TaskState.RUNNING;
        };
    }

    private Interaction.Button button() {
        return r.button == InteractEntityTaskRecord.Button.LEFT
                ? Interaction.Button.ATTACK : Interaction.Button.USE;
    }

    private boolean withinReach() {
        return player.onGround()
                && entity != null
                && player.distanceToSqr(entity.position()) <= REACH_SQR;
    }

    private String name() {
        return entity != null ? entity.getName().getString() : "entity#" + r.entityId;
    }

    private String describeDone() {
        String verb = r.button == InteractEntityTaskRecord.Button.LEFT ? "attacked" : "interacted with";
        return verb + " " + name();
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (nav != null) nav.stop();
        if (interaction != null) interaction.stop();
        Map<String, Object> data = new HashMap<>();
        data.put("button", r.button == InteractEntityTaskRecord.Button.LEFT ? "left" : "right");
        data.put("entity_id", r.entityId);
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case TIMEOUT -> TaskResult.timeout("timed out before interacting with " + name());
            case CANCELLED -> TaskResult.cancelled("interact_entity interrupted");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
