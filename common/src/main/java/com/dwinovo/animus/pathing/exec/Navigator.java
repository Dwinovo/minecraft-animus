package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.calc.AStar;
import com.dwinovo.animus.pathing.calc.AStarSearch;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.Path;
import net.minecraft.core.BlockPos;

import java.util.function.BooleanSupplier;

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
 * Constructed with the A* {@code goal} cell plus a {@code reached} predicate
 * (checked first every tick). {@link #tick()} returns {@link Status#ARRIVED}
 * exactly when {@code reached} is true, {@link Status#FAILED} when no usable
 * path exists after the replan budget, else {@link Status#RUNNING}.
 */
public final class Navigator {

    public enum Status { RUNNING, ARRIVED, FAILED }

    private static final int NODES_PER_TICK = AStar.DEFAULT_NODES_PER_TICK;
    /** Runaway guard on fresh (replan) searches; the task deadline is the real bound. */
    private static final int MAX_REPLANS = 40;
    /** Begin precomputing the continuation once the current path has this few moves left. */
    private static final int PRECOMPUTE_LOOKAHEAD = 3;

    private final AnimusEntity entity;
    private final BlockPos goal;
    private final double speed;
    private final BooleanSupplier reached;
    private final AStar astar = new AStar();

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

    public Navigator(AnimusEntity entity, BlockPos goal, double speed, BooleanSupplier reached) {
        this.entity = entity;
        this.goal = goal.immutable();
        this.speed = speed;
        this.reached = reached;
        startFreshSearch();   // begin planning from the entity's current position
    }

    public Status tick() {
        if (reached.getAsBoolean()) {
            return Status.ARRIVED;
        }

        // PLANNING mode: no path yet, spend this tick's budget on the fresh search.
        if (current == null) {
            return advanceFreshSearch();
        }

        // EXECUTING mode.
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
                return restartFresh();             // no continuation ready → plan fresh
            }
            case NEEDS_REPLAN -> {
                discardPrecompute();               // it was rooted at a now-irrelevant end
                return restartFresh();
            }
            case FAILED -> {
                return Status.FAILED;
            }
        }
        return Status.RUNNING;
    }

    // ---- PLANNING (fresh search from the live entity position) ----

    private void startFreshSearch() {
        searchCtx = new NavContext(entity);
        search = astar.newSearch(searchCtx, entity.blockPosition(), goal);
        if (PathExecutor.VERBOSE) {
            com.dwinovo.animus.Constants.LOG.info(
                    "[animus-nav#{}] plan #{} from {} -> {} (hasScaffold={})",
                    entity.getId(), replans, entity.blockPosition(), goal, searchCtx.hasScaffold);
        }
    }

    private Status advanceFreshSearch() {
        if (search.step(NODES_PER_TICK) == AStarSearch.State.COMPUTING) {
            return Status.RUNNING;   // resume next tick; entity simply waits, no freeze hack
        }
        Path path = search.result();
        boolean hadScaffold = searchCtx.hasScaffold;
        search = null;
        searchCtx = null;
        if (path == null || path.isEmpty()) {
            failReason = hadScaffold
                    ? "no path to target (obstructed)"
                    : "blocked by a gap and no bridging blocks — give me cobblestone or dirt";
            return reached.getAsBoolean() ? Status.ARRIVED : Status.FAILED;
        }
        current = new PathExecutor(entity, path, speed);
        return Status.RUNNING;
    }

    /** Tear down the current path and begin a fresh search from where the entity is. */
    private Status restartFresh() {
        if (current != null) {
            current.stop();
            current = null;
        }
        if (replans++ >= MAX_REPLANS) {
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
        nextCtx = new NavContext(entity);
        nextSearch = astar.newSearch(nextCtx, current.pathEnd(), goal);
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
