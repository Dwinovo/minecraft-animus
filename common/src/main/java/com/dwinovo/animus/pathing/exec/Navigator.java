package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.calc.AStar;
import com.dwinovo.animus.pathing.calc.AStarSearch;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.Path;
import net.minecraft.core.BlockPos;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The shared plan → execute → replan driver for any task that needs the
 * terrain-modifying pathfinder. Both {@code MoveToTaskGoal} and
 * {@code MineBlockTaskGoal} drive one of these instead of each re-implementing
 * the loop (and re-growing the same band-aids).
 *
 * <h2>Never freeze (path-while-moving)</h2>
 * The old per-goal loops froze the entity while a multi-tick A* search ran, then
 * walked, then froze again to replan. This driver instead, mirroring Baritone's
 * {@code current}/{@code next} executors:
 * <ul>
 *   <li>walks the current path, and</li>
 *   <li>when that path is <em>partial</em> and nearly walked, kicks off the next
 *       search rooted at the current path's <em>end</em> so it's ready to splice
 *       on the moment the entity arrives — no planning pause between segments.</li>
 * </ul>
 * A fresh search is only rooted at the live entity position on a genuine replan
 * (the executor was knocked off-path) or when an arrival had no continuation
 * ready. The executor's own validPositions-based re-localization tolerates the
 * small drift between "search start" and "entity now", so there's no stale-start
 * guard to maintain.
 *
 * <h2>Goal &amp; arrival</h2>
 * Constructed with a {@code goal} supplier (re-read each tick, so it can follow
 * a moving target — Baritone/mineflayer {@code GoalFollow}) plus a {@code reached}
 * predicate (checked first every tick). When the live goal drifts past
 * {@link #GOAL_MOVED_SQR} from the cell the current path was planned for, the
 * path is re-rooted at the live entity toward the new goal. {@link #tick()}
 * returns {@link Status#ARRIVED} exactly when {@code reached} is true,
 * {@link Status#FAILED} when no usable path exists after the replan budget, else
 * {@link Status#RUNNING}. A convenience constructor takes a fixed {@link BlockPos}
 * for stationary goals (move_to, auto_mine) — no follow behaviour.
 */
public final class Navigator {

    public enum Status { RUNNING, ARRIVED, FAILED }

    private static final int NODES_PER_TICK = AStar.DEFAULT_NODES_PER_TICK;
    /** Runaway guard on fresh (replan) searches; the task deadline is the real bound. */
    private static final int MAX_REPLANS = 40;
    /** Begin precomputing the continuation once the current path has this few moves left. */
    private static final int PRECOMPUTE_LOOKAHEAD = 3;
    /** The live goal must drift more than this (block²) from the planned goal to re-path. */
    private static final double GOAL_MOVED_SQR = 4.0;

    private final AnimusEntity entity;
    private final Supplier<BlockPos> goalSupplier;
    private final double speed;
    private final BooleanSupplier reached;
    private final AStar astar = new AStar();

    /** The goal cell the current search/path was built toward (for goal-moved detection). */
    private BlockPos plannedGoal;

    /** Currently walking this path (null while planning a fresh path from scratch). */
    private PathExecutor current;

    /** Fresh search rooted at the live entity (PLANNING mode, current == null). */
    private AStarSearch search;
    private NavContext searchCtx;

    /** Continuation search rooted at {@link PathExecutor#pathEnd()} while current walks. */
    private AStarSearch nextSearch;
    private NavContext nextCtx;
    /** Built from a finished {@link #nextSearch}; spliced in when current arrives. */
    private PathExecutor pendingNext;

    private int replans = 0;
    private String failReason = "target unreachable";

    /** Stationary goal (move_to / auto_mine) — a fixed cell, no follow. */
    public Navigator(AnimusEntity entity, BlockPos goal, double speed, BooleanSupplier reached) {
        this(entity, () -> goal, speed, reached);
    }

    /** Dynamic goal — re-read each tick so the path follows a moving target. */
    public Navigator(AnimusEntity entity, Supplier<BlockPos> goalSupplier, double speed,
                     BooleanSupplier reached) {
        this.entity = entity;
        this.goalSupplier = goalSupplier;
        this.speed = speed;
        this.reached = reached;
        startFreshSearch();   // begin planning from the entity's current position
    }

    public Status tick() {
        if (reached.getAsBoolean()) {
            return Status.ARRIVED;
        }
        // No water special-case: SWIM is a first-class movement primitive, so
        // a search rooted in water has edges (it swims out) and a path may
        // legitimately execute while submerged. The old yield-and-wait
        // apparatus (escape reflex hand-off, submerged timers, beach restart)
        // retired with it.

        // PLANNING mode: no path yet, spend this tick's budget on the fresh search.
        if (current == null) {
            return advanceFreshSearch();
        }

        // EXECUTING mode.
        // Follow a moving goal: if the live goal drifted far from what the current
        // path was planned toward, re-root at the live entity toward the new goal.
        // NOT counted against the replan budget — a moving target re-paths often.
        BlockPos liveGoal = goalSupplier.get();
        if (liveGoal == null) {
            failReason = "target lost";
            return Status.FAILED;
        }
        if (plannedGoal != null && liveGoal.distSqr(plannedGoal) > GOAL_MOVED_SQR) {
            discardPrecompute();
            return restartFresh(false);
        }

        maybePrecompute();
        advancePrecompute();

        switch (current.tick()) {
            case RUNNING -> { return Status.RUNNING; }
            case ARRIVED -> {
                replans = 0;                       // reached a path end: progress made
                if (reached.getAsBoolean()) {
                    return Status.ARRIVED;
                }
                if (pendingNext != null) {         // splice the precomputed continuation
                    current.stop();
                    current = pendingNext;
                    pendingNext = null;
                    return Status.RUNNING;
                }
                return restartFresh(true);         // no continuation ready → plan fresh
            }
            case NEEDS_REPLAN -> {
                discardPrecompute();               // it was rooted at a now-irrelevant end
                return restartFresh(true);
            }
            case FAILED -> {
                return Status.FAILED;
            }
        }
        return Status.RUNNING;
    }

    // ---- PLANNING (fresh search from the live entity position) ----

    private void startFreshSearch() {
        BlockPos g = goalSupplier.get();
        plannedGoal = g;
        searchCtx = new NavContext(entity);
        search = (g == null) ? null : astar.newSearch(searchCtx, entity.blockPosition(), g);
        if (PathExecutor.VERBOSE) {
            com.dwinovo.animus.Constants.LOG.info(
                    "[animus-nav#{}] plan #{} from {} -> {} (hasScaffold={})",
                    entity.getId(), replans, entity.blockPosition(), g, searchCtx.hasScaffold);
        }
    }

    private Status advanceFreshSearch() {
        if (search == null) {           // goal supplier returned null — target gone
            failReason = "target lost";
            return Status.FAILED;
        }
        if (search.step(NODES_PER_TICK) == AStarSearch.State.COMPUTING) {
            return Status.RUNNING;   // resume next tick; entity simply waits, no freeze hack
        }
        Path path = search.result();
        NavContext failedCtx = searchCtx;
        search = null;
        searchCtx = null;
        if (path == null || path.isEmpty()) {
            if (path != null && !path.partial) {
                // Complete-but-empty: the start node already satisfied the
                // near-goal tolerance (the goal cell itself is un-enterable —
                // e.g. the LLM targeted a furnace's own coordinates). We're as
                // close as terrain allows; let the task's own predicate decide.
                failReason = "already as close to the target as terrain allows";
                return reached.getAsBoolean() ? Status.ARRIVED : Status.FAILED;
            }
            if (!failedCtx.hasScaffold) {
                failReason = "blocked by a gap and no bridging blocks — give me cobblestone or dirt";
            } else {
                // Name the first concrete obstacle on the straight line so the
                // LLM gets a next step ("equip a pickaxe", "water in the way")
                // instead of a dead "obstructed".
                String why = (plannedGoal == null) ? null
                        : failedCtx.diagnoseObstruction(entity.blockPosition(), plannedGoal);
                failReason = (why != null)
                        ? "no path to target; the direct route is blocked by " + why
                        : "no path to target (obstructed)";
            }
            return reached.getAsBoolean() ? Status.ARRIVED : Status.FAILED;
        }
        current = new PathExecutor(entity, path, speed);
        return Status.RUNNING;
    }

    /**
     * Tear down the current path and begin a fresh search from where the entity
     * is. {@code budgeted} replans (off-path, arrival-without-continuation) count
     * against the runaway guard; goal-moved replans (a chased target moving) do
     * not, since a moving target re-paths continuously by design.
     */
    private Status restartFresh(boolean budgeted) {
        if (current != null) {
            current.stop();
            current = null;
        }
        if (budgeted && replans++ >= MAX_REPLANS) {
            failReason = "gave up after " + MAX_REPLANS + " replans";
            return reached.getAsBoolean() ? Status.ARRIVED : Status.FAILED;
        }
        startFreshSearch();
        return Status.RUNNING;
    }

    // ---- PATH-WHILE-MOVING (precompute the continuation of a partial path) ----

    private void maybePrecompute() {
        if (nextSearch != null || pendingNext != null) return;
        if (current == null || !current.isPartial()) return;        // complete path already reaches goal
        if (current.remainingMovements() > PRECOMPUTE_LOOKAHEAD) return;
        BlockPos g = goalSupplier.get();
        if (g == null) return;
        plannedGoal = g;
        nextCtx = new NavContext(entity);
        nextSearch = astar.newSearch(nextCtx, current.pathEnd(), g);
    }

    private void advancePrecompute() {
        if (nextSearch == null) return;
        if (nextSearch.step(NODES_PER_TICK) == AStarSearch.State.COMPUTING) return;
        Path np = nextSearch.result();
        nextSearch = null;
        nextCtx = null;
        if (np != null && !np.isEmpty()) {
            pendingNext = new PathExecutor(entity, np, speed);
        }
        // else: leave pendingNext null; on arrival we'll fall back to a fresh plan.
    }

    private void discardPrecompute() {
        nextSearch = null;
        nextCtx = null;
        if (pendingNext != null) {
            pendingNext.stop();
            pendingNext = null;
        }
    }

    // ---- accessors / teardown ----

    public int replans() {
        return replans;
    }

    public String failReason() {
        return failReason;
    }

    public void stop() {
        if (current != null) {
            current.stop();
            current = null;
        }
        discardPrecompute();
        search = null;
        searchCtx = null;
        entity.getNavigation().stop();
    }
}
