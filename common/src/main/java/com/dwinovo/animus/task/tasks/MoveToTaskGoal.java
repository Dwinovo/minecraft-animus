package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.Channel;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Executor for {@link MoveToTaskRecord}. Drives vanilla {@link PathNavigation}
 * toward the requested coordinates; resolves on one of:
 * <ul>
 *   <li>{@link TaskState#SUCCESS} — within {@link #REACHED_DISTANCE_SQR} blocks
 *       of target</li>
 *   <li>{@link TaskState#FAILED} — {@code navigation.moveTo} refused (unreachable),
 *       got stuck mid-path, or finished its path without reaching the target</li>
 *   <li>{@link TaskState#TIMEOUT} — base-class deadline hit (configured per
 *       record by the tool layer)</li>
 *   <li>{@link TaskState#CANCELLED} — vanilla selector evicted us, e.g. entity
 *       death</li>
 * </ul>
 *
 * <p>Channels = {@link Channel#LOCOMOTION} only — moveTo doesn't lock looking
 * or speech, so an LLM can issue {@code move_to + look_at + say} as a parallel
 * tool-call batch and the three goals run side-by-side.
 */
public final class MoveToTaskGoal extends LlmTaskGoal<MoveToTaskRecord> {

    /**
     * Squared-distance threshold for declaring success. ~2 blocks Euclidean.
     * Larger than vanilla's "reached" check inside {@code MoveToTargetSink}
     * because LLM-driven navigation is satisfied with approximate arrival —
     * we don't want a 1-block undershoot to register as failure.
     */
    private static final double REACHED_DISTANCE_SQR = 4.0;

    public MoveToTaskGoal(AnimusEntity entity) {
        super(entity, MoveToTaskRecord.TOOL_NAME, MoveToTaskRecord.class,
                EnumSet.of(Channel.LOCOMOTION));
    }

    @Override
    protected void onStart(MoveToTaskRecord r) {
        PathNavigation nav = entity.getNavigation();
        boolean ok = nav.moveTo(r.x, r.y, r.z, r.speed);
        if (!ok) {
            // createPath returned null (unreachable) or the entity is already
            // there but didn't trigger our REACHED check yet — distance test
            // below in onTick will sort the second case out.
            double distSqr = entity.distanceToSqr(r.x, r.y, r.z);
            if (distSqr <= REACHED_DISTANCE_SQR) {
                r.setState(TaskState.SUCCESS);
            } else {
                r.setState(TaskState.FAILED);
            }
        }
    }

    @Override
    protected void onTick(MoveToTaskRecord r) {
        double distSqr = entity.distanceToSqr(r.x, r.y, r.z);
        if (distSqr <= REACHED_DISTANCE_SQR) {
            r.setState(TaskState.SUCCESS);
            return;
        }
        PathNavigation nav = entity.getNavigation();
        if (nav.isStuck()) {
            r.setState(TaskState.FAILED);
            return;
        }
        if (nav.isDone()) {
            // Path concluded without us being close enough; vanilla aborted
            // pathfinding (probably a partial path that ended short).
            r.setState(TaskState.FAILED);
        }
    }

    @Override
    protected TaskResult buildResult(MoveToTaskRecord r, TaskState finalState) {
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

        return switch (finalState) {
            case SUCCESS -> TaskResult.ok("reached target", data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out before reaching target", true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "cancelled before reaching target", false, true, data);
            case FAILED -> TaskResult.fail(failureMessage(r), data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }

    private String failureMessage(MoveToTaskRecord r) {
        if (entity.getNavigation().isStuck()) return "stuck mid-path";
        return "target unreachable";
    }
}
