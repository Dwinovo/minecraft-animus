package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.PlaceManeuver;
import com.dwinovo.animus.pathing.exec.Placement;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.PlayerInv;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code place_block} on the player body: walk within reach (full pathfinding —
 * digs / bridges / climbs to get there), then place like a real player with the
 * shared {@link PlaceManeuver} "edge sneak" — hold sneak, edge to the block's rim
 * so the support face comes into view, look at it, and place natively.
 */
public final class PlaceBlockCompanionTask implements CompanionTask {

    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double WALK_SPEED = 1.0;

    private final AnimusPlayer player;
    private final PlaceBlockTaskRecord r;
    private PlayerNav nav;
    private PlaceManeuver maneuver;
    private String doneReason = "done";

    public PlaceBlockCompanionTask(AnimusPlayer player, PlaceBlockTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        var level = player.level();
        if (PlayerInv.count(player.getInventory(), r.item) <= 0) {
            fail("no " + r.label + " in inventory to place");
            return;
        }
        if (!BlockHelper.isReplaceableForPlacement(level, r.pos)) {
            fail("target " + coords() + " is already occupied");
            return;
        }
        if (!Placement.hasAnySupport(level, r.pos)) {
            fail("can't place a floating " + r.label + " at " + coords() + " — nothing adjacent to place against");
            return;
        }
        nav = new PlayerNav(player, r.pos, WALK_SPEED, this::withinReach);
    }

    @Override
    public TaskState tick() {
        if (player.level().getBlockState(r.pos).is(r.block)) {
            doneReason = "placed " + r.label + " at " + coords();
            return TaskState.SUCCESS;
        }
        if (withinReach()) {
            if (maneuver == null) {
                maneuver = new PlaceManeuver(player, r.pos,
                        () -> PlayerInv.findSlot(player.getInventory(), r.item),
                        () -> player.level().getBlockState(r.pos).is(r.block));
            }
            return switch (maneuver.tick()) {
                case DONE -> {
                    doneReason = "placed " + r.label + " at " + coords();
                    yield TaskState.SUCCESS;
                }
                case FAILED -> {
                    doneReason = maneuver.failReason();
                    yield TaskState.FAILED;
                }
                case RUNNING -> TaskState.RUNNING;
            };
        }
        if (nav == null) return TaskState.FAILED;
        return switch (nav.tick()) {
            case RUNNING, ARRIVED -> TaskState.RUNNING;
            case FAILED -> {
                doneReason = "can't reach a spot to place at " + coords() + " (" + nav.failReason() + ")";
                yield TaskState.FAILED;
            }
        };
    }

    private boolean withinReach() {
        return player.onGround() && player.distanceToSqr(Vec3.atCenterOf(r.pos)) <= REACH_SQR;
    }

    private String coords() {
        return r.pos.getX() + "," + r.pos.getY() + "," + r.pos.getZ();
    }

    private void fail(String reason) {
        doneReason = reason;
        r.setState(TaskState.FAILED);
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (nav != null) nav.stop();
        if (maneuver != null) maneuver.stop();
        Map<String, Object> data = new HashMap<>();
        data.put("block", r.label);
        data.put("x", r.pos.getX());
        data.put("y", r.pos.getY());
        data.put("z", r.pos.getZ());
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case TIMEOUT -> TaskResult.timeout("timed out before placing " + r.label + " at " + coords());
            case CANCELLED -> TaskResult.cancelled("place_block interrupted");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
