package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.exec.Interaction;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.CompanionTask;
import com.dwinovo.numen.core.task.PlayerInv;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.task.TaskState;
import net.minecraft.core.registries.BuiltInRegistries;
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

    private final NumenPlayer player;
    private final InteractEntityTaskRecord r;

    private Entity entity;
    private PlayerNav nav;
    private Interaction interaction;
    private long holdUntil = -1;
    private boolean acted = false;     // landed at least one press (death then = success, not failure)
    private String doneReason = "done";

    public InteractEntityCompanionTask(NumenPlayer player, InteractEntityTaskRecord record) {
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
        if (r.item != null && PlayerInv.count(player.getInventory(), r.item) <= 0) {
            doneReason = "don't have " + BuiltInRegistries.ITEM.getKey(r.item).getPath() + " to use on it";
            r.setState(TaskState.FAILED);
            return;
        }
        // Arrival = within reach AND a clear line of sight: nav keeps walking (toward the entity)
        // until BOTH hold, so a wall between us and the target is cleared by re-positioning rather
        // than stood in front of forever.
        nav = new PlayerNav(player, () -> entity.blockPosition(), WALK_SPEED, this::inReachAndLos);
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

        // A fixed-duration hold completes on time even if the line of sight lapsed near the end.
        if (interaction != null && holdUntil >= 0 && player.level().getGameTime() >= holdUntil) {
            interaction.stop();
            doneReason = describeDone();
            return TaskState.SUCCESS;
        }

        // Follow + re-position until we're in reach with a clear line of sight.
        if (!inReachAndLos()) {
            if (nav == null) return TaskState.FAILED;
            return switch (nav.tick()) {
                case RUNNING, ARRIVED -> TaskState.RUNNING;
                case FAILED -> {
                    doneReason = "can't reach " + name() + ": " + nav.failReason();
                    yield TaskState.FAILED;
                }
            };
        }

        // In reach + LOS: aim at the entity and confirm the crosshair actually resolves to IT
        // (e.g. not another entity wandered into the exact line) before pressing.
        InputDriver.lookAt(player, entity.getEyePosition());
        HitResult hit = Interaction.nativeRaytrace(player, REACH);
        boolean onTarget = hit.getType() == HitResult.Type.ENTITY
                && ((EntityHitResult) hit).getEntity() == entity;
        if (!onTarget) {
            return TaskState.RUNNING;   // settling / something briefly in the line — re-aim next tick
        }

        if (interaction == null) {
            if (r.item != null) {
                player.holdInHand(PlayerInv.findSlot(player.getInventory(), r.item));
            }
            interaction = Interaction.forHit(player, hit, button(), r.holdTicks);
            if (r.holdTicks > 0) {
                holdUntil = player.level().getGameTime() + r.holdTicks;
            }
        }
        acted = true;

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

    /** In arm's reach AND no block between our eyes and the entity (vanilla hasLineOfSight) —
     *  the nav arrival gate, so the body walks around a wall instead of freezing in front of it. */
    private boolean inReachAndLos() {
        return withinReach() && player.hasLineOfSight(entity);
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
