package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;

import java.util.Map;

/**
 * Executor for {@link WaitTaskRecord}: do nothing, on purpose, for the
 * requested duration. Uses game time (freeze/tick-rate aware) and stays a
 * normal task so the owner's Stop button interrupts it like anything else.
 */
public final class WaitTaskGoal extends LlmTaskGoal<WaitTaskRecord> {

    private long wakeAtGameTime;

    public WaitTaskGoal(AnimusEntity entity) {
        super(entity, WaitTaskRecord.TOOL_NAME, WaitTaskRecord.class);
    }

    @Override
    protected void onStart(WaitTaskRecord r) {
        this.wakeAtGameTime = entity.level().getGameTime() + r.seconds * 20L;
        entity.getNavigation().stop();   // settle in place; we're idling deliberately
    }

    @Override
    protected void onTick(WaitTaskRecord r) {
        if (entity.level().getGameTime() >= wakeAtGameTime) {
            currentRecord.setState(TaskState.SUCCESS);
        }
    }

    @Override
    protected TaskResult buildResult(WaitTaskRecord r, TaskState finalState) {
        Map<String, Object> data = Map.of("seconds", r.seconds);
        String label = r.seconds + "s" + (r.reason.isEmpty() ? "" : " (" + r.reason + ")");
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok("waited " + label, data);
            case CANCELLED -> TaskResult.cancelled("wait interrupted before " + label + " elapsed");
            // The deadline is stamped past the wait itself, so TIMEOUT here is a bug guard.
            case TIMEOUT -> TaskResult.timeout("wait timed out unexpectedly");
            default -> TaskResult.fail("unexpected state: " + finalState);
        };
    }
}
