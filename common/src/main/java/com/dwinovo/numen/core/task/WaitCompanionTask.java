package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.task.CompanionTask;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.task.TaskState;

import java.util.Map;

/**
 * {@code wait} on the player body: idle in place for the requested duration
 * (game-time based, freeze/tick-rate aware). Player-body twin of WaitTaskGoal.
 */
public final class WaitCompanionTask implements CompanionTask {

    private final NumenPlayer player;
    private final WaitTaskRecord r;
    private long wakeAtGameTime;

    public WaitCompanionTask(NumenPlayer player, WaitTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        this.wakeAtGameTime = player.level().getGameTime() + r.seconds * 20L;
        InputDriver.halt(player);
    }

    @Override
    public TaskState tick() {
        InputDriver.halt(player);   // hold still while idling
        return player.level().getGameTime() >= wakeAtGameTime ? TaskState.SUCCESS : TaskState.RUNNING;
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        Map<String, Object> data = Map.of("seconds", r.seconds);
        String label = r.seconds + "s" + (r.reason.isEmpty() ? "" : " (" + r.reason + ")");
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok("waited " + label, data);
            case CANCELLED -> TaskResult.cancelled("wait interrupted before " + label + " elapsed");
            case TIMEOUT -> TaskResult.timeout("wait timed out unexpectedly");
            default -> TaskResult.fail("unexpected state: " + finalState);
        };
    }
}
