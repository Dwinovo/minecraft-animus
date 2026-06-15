package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.Interaction;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code interact} on the player body: walk within reach of the target (a block
 * or an entity), then run the native {@link Interaction} for the requested button
 * to completion. The thin task wrapper over the lowest-level interaction
 * primitive — break_block / place_block / eat_item are specialised siblings; this
 * is the generic "press a button on this thing" used for everything else.
 */
public final class InteractCompanionTask implements CompanionTask {

    private static final double BLOCK_REACH_SQR = 4.5 * 4.5;
    private static final double ENTITY_REACH_SQR = 3.0 * 3.0;
    private static final double WALK_SPEED = 1.0;

    private final AnimusPlayer player;
    private final InteractTaskRecord r;

    private Entity entity;             // resolved for ENTITY targets
    private PlayerNav nav;
    private Interaction interaction;
    private String doneReason = "done";

    public InteractCompanionTask(AnimusPlayer player, InteractTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        if (r.targetKind == InteractTaskRecord.TargetKind.BLOCK) {
            nav = new PlayerNav(player, r.block, WALK_SPEED, this::withinReach);
            return;
        }
        // Entity target: resolve by id; path follows its live position.
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
        if (r.targetKind == InteractTaskRecord.TargetKind.ENTITY
                && (entity == null || !entity.isAlive())) {
            doneReason = "the target entity is gone";
            return TaskState.FAILED;
        }
        if (withinReach()) {
            if (interaction == null) interaction = buildInteraction();
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
        if (nav == null) return TaskState.FAILED;
        return switch (nav.tick()) {
            case RUNNING, ARRIVED -> TaskState.RUNNING;
            case FAILED -> {
                doneReason = "can't reach the target: " + nav.failReason();
                yield TaskState.FAILED;
            }
        };
    }

    private Interaction buildInteraction() {
        boolean attack = r.button == InteractTaskRecord.Button.ATTACK;
        if (r.targetKind == InteractTaskRecord.TargetKind.BLOCK) {
            return attack
                    ? Interaction.attackBlock(player, r.block)
                    : Interaction.useBlock(player, r.block, InteractionHand.MAIN_HAND);
        }
        return attack
                ? Interaction.attackEntity(player, entity)
                : Interaction.useEntity(player, entity, InteractionHand.MAIN_HAND);
    }

    private boolean withinReach() {
        if (!player.onGround()) return false;
        if (r.targetKind == InteractTaskRecord.TargetKind.BLOCK) {
            return player.distanceToSqr(Vec3.atCenterOf(r.block)) <= BLOCK_REACH_SQR;
        }
        return entity != null && player.distanceToSqr(entity.position()) <= ENTITY_REACH_SQR;
    }

    private String describeDone() {
        String verb = r.button == InteractTaskRecord.Button.USE ? "used" : "attacked";
        if (r.targetKind == InteractTaskRecord.TargetKind.BLOCK) {
            return verb + " block at " + r.block.getX() + "," + r.block.getY() + "," + r.block.getZ();
        }
        return verb + " " + entity.getName().getString();
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (nav != null) nav.stop();
        if (interaction != null) interaction.stop();
        Map<String, Object> data = new HashMap<>();
        data.put("button", r.button == InteractTaskRecord.Button.USE ? "use" : "attack");
        if (r.targetKind == InteractTaskRecord.TargetKind.BLOCK) {
            data.put("x", r.block.getX());
            data.put("y", r.block.getY());
            data.put("z", r.block.getZ());
        } else {
            data.put("entity_id", r.entityId);
        }
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case TIMEOUT -> TaskResult.timeout("timed out before interacting");
            case CANCELLED -> TaskResult.cancelled("interact interrupted");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
