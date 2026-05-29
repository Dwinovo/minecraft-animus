package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.calc.AStar;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.Path;
import com.dwinovo.animus.pathing.exec.PathExecutor;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for {@link MoveToTaskRecord}. Drives a Baritone-style
 * terrain-modifying pathfinder ({@link com.dwinovo.animus.pathing}) toward the
 * requested coordinates: the entity walks, bridges gaps with cobblestone/dirt
 * from its own inventory, mines through obstructions, and steps up ledges —
 * all costed by what it actually carries.
 *
 * <h2>Plan → execute → replan loop</h2>
 * {@link #onStart} computes an A* path and hands it to a {@link PathExecutor}.
 * Each tick the executor advances one movement; when it reports
 * {@code NEEDS_REPLAN} (ran out of scaffolding, world changed, stuck) we run a
 * fresh search from the current position with a fresh inventory snapshot. A
 * replan budget bounds the loop so a genuinely-unreachable goal fails instead
 * of spinning.
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

    /**
     * Max A* searches per task (initial + replans) before giving up. Generous
     * because each partial-path waypoint legitimately consumes one; the
     * base-class deadline is the real upper bound on a runaway task.
     */
    private static final int MAX_REPLANS = 30;

    private final AStar astar = new AStar();
    private PathExecutor executor;
    private int replans = 0;
    private String lastFailReason = "target unreachable";

    public MoveToTaskGoal(AnimusEntity entity) {
        super(entity, MoveToTaskRecord.TOOL_NAME, MoveToTaskRecord.class);
    }

    @Override
    protected void onStart(MoveToTaskRecord r) {
        if (closeEnough(r)) {
            r.setState(TaskState.SUCCESS);
            return;
        }
        if (!plan(r)) {
            r.setState(TaskState.FAILED);
        }
    }

    @Override
    protected void onTick(MoveToTaskRecord r) {
        if (closeEnough(r)) {
            r.setState(TaskState.SUCCESS);
            return;
        }
        if (executor == null) {
            r.setState(TaskState.FAILED);
            return;
        }
        switch (executor.tick()) {
            case RUNNING -> { /* keep going */ }
            case ARRIVED -> {
                if (closeEnough(r)) {
                    r.setState(TaskState.SUCCESS);
                } else if (!plan(r)) {            // arrived at a waypoint, push on
                    r.setState(closeEnough(r) ? TaskState.SUCCESS : TaskState.FAILED);
                }
            }
            case NEEDS_REPLAN -> {
                if (!plan(r)) {
                    r.setState(closeEnough(r) ? TaskState.SUCCESS : TaskState.FAILED);
                }
            }
            case FAILED -> r.setState(TaskState.FAILED);
        }
    }

    /**
     * Compute a fresh path from the entity's current position to the target
     * and install a new executor. Returns false when out of replan budget or
     * the search produced nothing usable.
     */
    private boolean plan(MoveToTaskRecord r) {
        if (executor != null) executor.stop();
        if (replans++ >= MAX_REPLANS) {
            lastFailReason = "gave up after " + MAX_REPLANS + " replans";
            return false;
        }
        NavContext ctx = new NavContext(entity);
        BlockPos start = entity.blockPosition();
        BlockPos goal = BlockPos.containing(r.x, r.y, r.z);
        Path path = astar.compute(ctx, start, goal);

        if (path.isEmpty()) {
            lastFailReason = ctx.hasScaffold
                    ? "no path to target (obstructed)"
                    : "blocked by a gap and no bridging blocks — give me cobblestone or dirt";
            return false;
        }
        executor = new PathExecutor(entity, path, r.speed);
        return true;
    }

    private boolean closeEnough(MoveToTaskRecord r) {
        return entity.distanceToSqr(r.x, r.y, r.z) <= REACHED_DISTANCE_SQR;
    }

    @Override
    protected TaskResult buildResult(MoveToTaskRecord r, TaskState finalState) {
        if (executor != null) executor.stop();
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
            case FAILED -> TaskResult.fail(lastFailReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
