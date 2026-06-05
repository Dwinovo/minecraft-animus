package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.exec.Navigator;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for {@link MoveToTaskRecord}. Drives the shared {@link Navigator}
 * (the terrain-modifying pathfinder) toward the requested coordinates: the
 * entity walks, bridges gaps with cobblestone/dirt from its own inventory,
 * mines through obstructions, and steps up ledges — all costed by what it
 * actually carries, planned while it keeps moving.
 *
 * <h2>Outcomes</h2>
 * <ul>
 *   <li>{@code SUCCESS} — within {@link #REACHED_DISTANCE_SQR} of the target.</li>
 *   <li>{@code FAILED} — no path even after replans (often: no bridging blocks
 *       and a gap in the way — the message hints to supply cobblestone/dirt).</li>
 *   <li>{@code TIMEOUT} / {@code CANCELLED} — base-class deadline / eviction.</li>
 * </ul>
 */
public final class MoveToTaskGoal extends LlmTaskGoal<MoveToTaskRecord> {

    /** ~2 blocks Euclidean — LLM navigation is happy with approximate arrival. */
    private static final double REACHED_DISTANCE_SQR = 4.0;

    private Navigator nav;

    public MoveToTaskGoal(AnimusEntity entity) {
        super(entity, MoveToTaskRecord.TOOL_NAME, MoveToTaskRecord.class);
    }

    @Override
    protected void onStart(MoveToTaskRecord r) {
        if (closeEnough(r)) {
            r.setState(TaskState.SUCCESS);
            return;
        }
        nav = new Navigator(entity, BlockPos.containing(r.x, r.y, r.z), r.speed, () -> closeEnough(r));
    }

    @Override
    protected void onTick(MoveToTaskRecord r) {
        if (nav == null) {
            r.setState(TaskState.FAILED);
            return;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* keep going */ }
            // ARRIVED is returned exactly when our reached-predicate (closeEnough)
            // is true; FAILED means no usable path — either way the task is over.
            case ARRIVED -> r.setState(TaskState.SUCCESS);
            case FAILED -> r.setState(closeEnough(r) ? TaskState.SUCCESS : TaskState.FAILED);
        }
    }

    private boolean closeEnough(MoveToTaskRecord r) {
        return entity.distanceToSqr(r.x, r.y, r.z) <= REACHED_DISTANCE_SQR;
    }

    @Override
    protected TaskResult buildResult(MoveToTaskRecord r, TaskState finalState) {
        int replans = nav != null ? nav.replans() : 0;
        String failReason = nav != null ? nav.failReason() : "target unreachable";
        if (nav != null) nav.stop();
        entity.getNavigation().stop();

        Map<String, Object> data = new HashMap<>();
        data.put("final_x", entity.getX());
        data.put("final_y", entity.getY());
        data.put("final_z", entity.getZ());
        data.put("target_x", r.x);
        data.put("target_y", r.y);
        data.put("target_z", r.z);
        double remaining = Math.sqrt(entity.distanceToSqr(r.x, r.y, r.z));
        data.put("distance_remaining", remaining);
        data.put("replans", replans);

        return switch (finalState) {
            case SUCCESS -> TaskResult.ok("reached target", data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out before reaching target", true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "cancelled before reaching target", false, true, data);
            case FAILED -> TaskResult.fail(failReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
