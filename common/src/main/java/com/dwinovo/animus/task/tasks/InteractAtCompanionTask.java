package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.InputDriver;
import com.dwinovo.animus.pathing.exec.Interaction;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.PlayerInv;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code interact_at} on the player body — the point-aimed native interaction (BLOCK + AIR).
 * Walk within reach of the aim (if one is given), look at it, fire ONE native crosshair
 * raytrace ({@link Interaction#nativeRaytrace}) and press the requested mouse button on
 * whatever it resolves to ({@link Interaction#forHit}): break / activate the block hit, or —
 * on a clear-air aim — use the held item in that direction (throw / eat / draw). The mouse
 * model is the two record fields {@code button} (left/right) × {@code holdTicks} (tap/hold).
 */
public final class InteractAtCompanionTask implements CompanionTask {

    private static final double REACH = 4.5;
    private static final double REACH_SQR = REACH * REACH;
    private static final double WALK_SPEED = 1.0;

    private final AnimusPlayer player;
    private final InteractAtTaskRecord r;

    private PlayerNav nav;
    private Interaction interaction;
    private long holdUntil = -1;       // game tick to release a fixed-duration hold (holdTicks > 0)
    private String doneReason = "done";

    public InteractAtCompanionTask(AnimusPlayer player, InteractAtTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        // If an item to use was named, fail fast unless we actually carry it.
        if (r.item != null && PlayerInv.count(player.getInventory(), r.item) <= 0) {
            doneReason = "don't have " + BuiltInRegistries.ITEM.getKey(r.item).getPath() + " to use";
            r.setState(TaskState.FAILED);
            return;
        }
        // An aim point → walk within arm's reach of it first. No aim → act from where we stand,
        // facing forward (in-air use, e.g. throwing straight ahead).
        if (r.aim != null) {
            nav = new PlayerNav(player, r.aim, WALK_SPEED, this::withinReach);
        }
    }

    @Override
    public TaskState tick() {
        // 1) Travel to within reach of the aim.
        if (r.aim != null && !withinReach()) {
            if (nav == null) return TaskState.FAILED;
            return switch (nav.tick()) {
                case RUNNING, ARRIVED -> TaskState.RUNNING;
                case FAILED -> {
                    doneReason = "can't reach " + aimLabel() + ": " + nav.failReason();
                    yield TaskState.FAILED;
                }
            };
        }

        // 2) Resolve the crosshair once we're in position, then drive the action.
        if (interaction == null) {
            if (r.item != null) {
                player.holdInHand(PlayerInv.findSlot(player.getInventory(), r.item));
            }
            if (r.aim != null) {
                InputDriver.lookAt(player, Vec3.atCenterOf(r.aim));
            }
            HitResult hit = Interaction.nativeRaytrace(player, REACH);
            // A consumable / ender pearl used in the AIR is body-bound (would feed or teleport the
            // fake player) — refuse even when it's just whatever happened to be in hand.
            if (button() == Interaction.Button.USE && hit.getType() == HitResult.Type.MISS) {
                String reason = InteractAtTaskRecord.bodyBoundReason(player.getMainHandItem().getItem());
                if (reason != null) {
                    doneReason = reason;
                    return TaskState.FAILED;
                }
            }
            interaction = Interaction.forHit(player, hit, button(), r.holdTicks);
            if (interaction == null) {       // left-click on air — a swing, nothing to do
                doneReason = "nothing under the aim (left-click in the air)";
                return TaskState.SUCCESS;
            }
            if (r.holdTicks > 0) {
                holdUntil = player.level().getGameTime() + r.holdTicks;
            }
        }

        // 3) A fixed-duration hold ends when its window elapses (Carpet: release the button).
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
        return r.button == InteractAtTaskRecord.Button.LEFT
                ? Interaction.Button.ATTACK : Interaction.Button.USE;
    }

    private boolean withinReach() {
        return player.onGround()
                && player.distanceToSqr(Vec3.atCenterOf(r.aim)) <= REACH_SQR;
    }

    private String aimLabel() {
        return r.aim.getX() + "," + r.aim.getY() + "," + r.aim.getZ();
    }

    private String describeDone() {
        String verb = r.button == InteractAtTaskRecord.Button.LEFT ? "left-clicked" : "right-clicked";
        return verb + (r.aim != null ? " " + aimLabel() : " (forward)");
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (nav != null) nav.stop();
        if (interaction != null) interaction.stop();
        Map<String, Object> data = new HashMap<>();
        data.put("button", r.button == InteractAtTaskRecord.Button.LEFT ? "left" : "right");
        if (r.aim != null) {
            data.put("x", r.aim.getX());
            data.put("y", r.aim.getY());
            data.put("z", r.aim.getZ());
        }
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case TIMEOUT -> TaskResult.timeout("timed out before interacting at " + (r.aim != null ? aimLabel() : "forward"));
            case CANCELLED -> TaskResult.cancelled("interact_at interrupted");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
