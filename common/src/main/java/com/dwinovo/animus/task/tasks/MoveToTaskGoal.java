package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.calc.AStar;
import com.dwinovo.animus.pathing.calc.AStarSearch;
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
 * Each task alternates between two per-tick modes:
 * <ul>
 *   <li><b>PLANNING</b> — a {@link AStarSearch time-sliced A* search} is
 *       advanced by a bounded node budget every tick until it produces a path,
 *       so a far/complex target never stalls the server tick (mineflayer-style
 *       cooperative slicing, all on the tick thread reading the live world).</li>
 *   <li><b>EXECUTING</b> — a {@link PathExecutor} advances one movement per
 *       tick; when it reports {@code NEEDS_REPLAN} (ran out of scaffolding,
 *       world changed, stuck) or {@code ARRIVED} on a partial path we drop back
 *       to PLANNING with a fresh inventory snapshot.</li>
 * </ul>
 * A replan budget bounds the loop so a genuinely-unreachable goal fails instead
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

    /** Node-expansion budget granted to the in-flight search each tick. */
    private static final int NODES_PER_TICK = AStar.DEFAULT_NODES_PER_TICK;

    private final AStar astar = new AStar();

    /** Non-null while PLANNING; the search being stepped toward a path. */
    private AStarSearch search;
    /** Inventory snapshot backing {@link #search}; kept for the fail message. */
    private NavContext planningCtx;
    /** Non-null while EXECUTING; drives the entity along the computed path. */
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
        if (!startPlanning(r)) {
            r.setState(TaskState.FAILED);
        }
    }

    @Override
    protected void onTick(MoveToTaskRecord r) {
        if (closeEnough(r)) {
            r.setState(TaskState.SUCCESS);
            return;
        }
        // PLANNING: spend this tick's node budget on the in-flight search.
        if (search != null) {
            // Hold position while the (time-sliced) search runs. Otherwise the
            // MoveControl keeps driving toward the PREVIOUS path's last wanted
            // node, drifting the entity off the cell this search is computing
            // from — which would make the finished path start in the wrong place.
            entity.getMoveControl().setWantedPosition(
                    entity.getX(), entity.getY(), entity.getZ(), 0.0);
            advancePlanning(r);
            return;
        }
        // EXECUTING: advance the path one movement.
        if (executor == null) {
            r.setState(TaskState.FAILED);
            return;
        }
        switch (executor.tick()) {
            case RUNNING -> { /* keep going */ }
            case ARRIVED -> {
                if (closeEnough(r)) {
                    r.setState(TaskState.SUCCESS);
                } else if (!startPlanning(r)) {    // arrived at a waypoint, push on
                    r.setState(closeEnough(r) ? TaskState.SUCCESS : TaskState.FAILED);
                }
            }
            case NEEDS_REPLAN -> {
                if (!startPlanning(r)) {
                    r.setState(closeEnough(r) ? TaskState.SUCCESS : TaskState.FAILED);
                }
            }
            case FAILED -> r.setState(TaskState.FAILED);
        }
    }

    /**
     * Enter PLANNING: tear down any executor, snapshot the inventory, and begin
     * a fresh time-sliced search from the entity's current position. Returns
     * false (and sets {@link #lastFailReason}) when the replan budget is spent;
     * the search itself is stepped over subsequent ticks in
     * {@link #advancePlanning}.
     */
    private boolean startPlanning(MoveToTaskRecord r) {
        if (executor != null) {
            executor.stop();
            executor = null;
        }
        if (replans++ >= MAX_REPLANS) {
            lastFailReason = "gave up after " + MAX_REPLANS + " replans";
            return false;
        }
        planningCtx = new NavContext(entity);
        BlockPos start = entity.blockPosition();
        BlockPos goal = BlockPos.containing(r.x, r.y, r.z);
        search = astar.newSearch(planningCtx, start, goal);
        if (com.dwinovo.animus.pathing.exec.PathExecutor.VERBOSE) {
            com.dwinovo.animus.Constants.LOG.info(
                    "[animus-move#{}] replan #{} from {} -> goal {} (hasScaffold={})",
                    entity.getId(), replans, start, goal, planningCtx.hasScaffold);
        }
        return true;
    }

    /**
     * Spend one tick's node budget on the in-flight search. While it's still
     * computing the entity simply waits; once a path is ready we install an
     * executor (EXECUTING) or fail if the search found nothing usable.
     */
    private void advancePlanning(MoveToTaskRecord r) {
        if (search.step(NODES_PER_TICK) == AStarSearch.State.COMPUTING) {
            return;  // resume next tick
        }
        Path path = search.result();
        boolean hadScaffold = planningCtx.hasScaffold;
        search = null;
        planningCtx = null;

        if (path == null || path.isEmpty()) {
            lastFailReason = hadScaffold
                    ? "no path to target (obstructed)"
                    : "blocked by a gap and no bridging blocks — give me cobblestone or dirt";
            if (PathExecutor.VERBOSE) {
                com.dwinovo.animus.Constants.LOG.info("[animus-move#{}] A* found no usable path: {}",
                        entity.getId(), lastFailReason);
            }
            r.setState(closeEnough(r) ? TaskState.SUCCESS : TaskState.FAILED);
            return;
        }
        // ROOT GUARD: the time-sliced A* captured its start when planning began,
        // but the entity can fall / be pushed during the multi-tick search. If it
        // has left that start cell, every node in this path is offset from the
        // entity's real position — executing it drives the entity along a column
        // it isn't in (the "infinite pillar bounce" bug). Re-search from where the
        // entity actually is rather than run a stale path.
        if (!entity.blockPosition().equals(path.start)) {
            if (PathExecutor.VERBOSE) {
                com.dwinovo.animus.Constants.LOG.info(
                        "[animus-move#{}] entity left path start during planning ({} -> {}); re-search from current",
                        entity.getId(), path.start, entity.blockPosition());
            }
            if (!startPlanning(r)) {
                r.setState(closeEnough(r) ? TaskState.SUCCESS : TaskState.FAILED);
            }
            return;
        }
        executor = new PathExecutor(entity, path, r.speed);
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
