package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code move_to} on the companion player body: drives {@link PlayerNav} toward
 * the requested coordinates (walk, bridge, mine through, step up — all costed by
 * what it carries). The player-body twin of {@code MoveToTaskGoal}.
 */
public final class MoveToCompanionTask implements CompanionTask {

    private static final double REACHED_DISTANCE_SQR = 4.0;
    private static final long TICKS_PER_BLOCK = 20;
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;

    private final AnimusPlayer player;
    private final MoveToTaskRecord r;
    private PlayerNav nav;

    public MoveToCompanionTask(AnimusPlayer player, MoveToTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        if (closeEnough()) {
            r.setState(TaskState.SUCCESS);
            return;
        }
        double dist = Math.sqrt(player.distanceToSqr(r.x, r.y, r.z));
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (dist * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        nav = new PlayerNav(player, BlockPos.containing(r.x, r.y, r.z), r.speed, this::closeEnough);
    }

    @Override
    public TaskState tick() {
        if (nav == null) return TaskState.FAILED;
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> TaskState.SUCCESS;
            case FAILED -> closeEnough() ? TaskState.SUCCESS : TaskState.FAILED;
        };
    }

    private boolean closeEnough() {
        return player.distanceToSqr(r.x, r.y, r.z) <= REACHED_DISTANCE_SQR;
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        String failReason = nav != null ? nav.failReason() : "target unreachable";
        if (nav != null) nav.stop();

        Map<String, Object> data = new HashMap<>();
        data.put("final_x", player.getX());
        data.put("final_y", player.getY());
        data.put("final_z", player.getZ());
        data.put("distance_remaining", Math.sqrt(player.distanceToSqr(r.x, r.y, r.z)));

        return switch (finalState) {
            case SUCCESS -> TaskResult.ok("reached target", data);
            case TIMEOUT -> new TaskResult(false, "timed out before reaching target", true, false, data);
            case CANCELLED -> new TaskResult(false, "cancelled before reaching target", false, true, data);
            case FAILED -> TaskResult.fail(failReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
