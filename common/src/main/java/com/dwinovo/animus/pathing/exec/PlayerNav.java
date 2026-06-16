package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.calc.AStar;
import com.dwinovo.animus.pathing.calc.AStarSearch;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.NavGoal;
import com.dwinovo.animus.pathing.calc.Path;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.pathing.viz.PathVizPublisher;
import com.dwinovo.animus.pathing.util.PathSettings;
import net.minecraft.core.BlockPos;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Plan → execute → replan driver for a companion {@link AnimusPlayer} body.
 * The player-body twin of {@code Navigator}: same path-while-moving loop (walk
 * the current path; precompute the continuation of a partial path so there's no
 * planning pause at a segment boundary; re-root on a moving goal or after an
 * off-path replan) but executing through {@link PlayerPathExecutor}. The A*
 * planner ({@link NavContext}/{@link AStar}) is body-neutral and reused as-is.
 *
 * <p>Internally the goal is a {@link NavGoal} so callers can target a single
 * cell ({@link #PlayerNav(AnimusPlayer, BlockPos, double, BooleanSupplier)}) or
 * a richer goal — a {@link NavGoal#composite composite} ore field, a mining
 * stance — via {@link #toGoal}.
 */
public final class PlayerNav {

    public enum Status { RUNNING, ARRIVED, FAILED }

    private static final int MAX_REPLANS = 40;
    private static final double GOAL_MOVED_SQR = 4.0;

    private final AnimusPlayer player;
    private final Supplier<NavGoal> goalSupplier;
    private final double speed;
    private final BooleanSupplier reached;
    private final AStar astar = new AStar();

    private BlockPos plannedCenter;
    private PlayerPathExecutor current;

    // A* now runs on the planner pool, not stepped on the tick thread. We hold the future (polled each
    // tick) plus the search object itself (to cancel it on replan/stop so a stale worker stops wasting
    // CPU). One in-flight search at a time for the main path, one for the precomputed next segment.
    private java.util.concurrent.CompletableFuture<Path> searchFuture;
    private AStarSearch searchObj;
    private java.util.concurrent.CompletableFuture<Path> nextFuture;
    private AStarSearch nextObj;
    private PlayerPathExecutor pendingNext;
    private Path pendingPathForViz;

    /** Packed positions of the path we're currently executing — fed to the next
     *  search as Baritone's {@code Favoring} so a replan reuses this route (damps
     *  the flip-flopping a from-scratch replan would otherwise cause). */
    private it.unimi.dsi.fastutil.longs.LongSet previousPathHashes =
            it.unimi.dsi.fastutil.longs.LongSets.emptySet();

    /** Cells to highlight in the overlay; null → just the path's destination.
     *  The mining task sets this to its whole known-ore field (Baritone boxes
     *  every GoalComposite member). */
    private Supplier<java.util.List<BlockPos>> highlights;

    /** Highlight these cells in the path overlay (e.g. the full ore field). */
    public void setHighlights(Supplier<java.util.List<BlockPos>> highlights) {
        this.highlights = highlights;
    }

    private void publishViz(Path cut) {
        java.util.List<BlockPos> targets =
                highlights != null ? highlights.get() : java.util.List.of(cut.end);
        PathVizPublisher.publish(player, cut, targets);
    }

    private int replans = 0;
    private String failReason = "target unreachable";

    /** Walk to a single cell. */
    public PlayerNav(AnimusPlayer player, BlockPos goal, double speed, BooleanSupplier reached) {
        this(player, () -> resolveBlockGoal(player, goal), speed, reached, true);
    }

    /** Walk to a (possibly moving) single cell. */
    public PlayerNav(AnimusPlayer player, Supplier<BlockPos> goalSupplier, double speed,
                     BooleanSupplier reached) {
        this(player, () -> {
            BlockPos g = goalSupplier.get();
            return g == null ? null : resolveBlockGoal(player, g);
        }, speed, reached, true);
    }

    /** Walk toward an arbitrary {@link NavGoal} (composite ore field, mining stance, …). */
    public static PlayerNav toGoal(AnimusPlayer player, Supplier<NavGoal> goalSupplier,
                                   double speed, BooleanSupplier reached) {
        return new PlayerNav(player, goalSupplier, speed, reached, true);
    }

    private PlayerNav(AnimusPlayer player, Supplier<NavGoal> goalSupplier, double speed,
                      BooleanSupplier reached, boolean marker) {
        this.player = player;
        this.goalSupplier = goalSupplier;
        this.speed = speed;
        this.reached = reached;
        startFreshSearch();
    }

    /** A cell goal: exact if standable, else reach within 2 (mirrors move_to arrival). */
    private static NavGoal resolveBlockGoal(AnimusPlayer player, BlockPos bp) {
        return BlockHelper.canWalkThrough(player.level(), bp)
                ? NavGoal.exact(bp)
                : NavGoal.near(bp, 2.0);
    }

    public Status tick() {
        if (reached.getAsBoolean()) return Status.ARRIVED;

        if (current == null) {
            return advanceFreshSearch();
        }

        NavGoal liveGoal = goalSupplier.get();
        if (liveGoal == null) {
            failReason = "target lost";
            return Status.FAILED;
        }
        if (plannedCenter != null && liveGoal.center().distSqr(plannedCenter) > GOAL_MOVED_SQR) {
            discardPrecompute();
            return restartFresh(false);
        }

        maybePrecompute();
        advancePrecompute();

        switch (current.tick()) {
            case RUNNING -> { return Status.RUNNING; }
            case ARRIVED -> {
                replans = 0;
                if (reached.getAsBoolean()) return Status.ARRIVED;
                if (pendingNext != null) {
                    // Hand off to the precomputed segment WITHOUT halting — calling
                    // current.stop() zeroes the inputs for a tick and causes a visible
                    // hitch at every segment boundary. pendingNext takes over the
                    // inputs on its first tick, so motion stays continuous.
                    current = pendingNext;
                    pendingNext = null;
                    if (pendingPathForViz != null) {
                        publishViz(pendingPathForViz);
                        pendingPathForViz = null;
                    }
                    return Status.RUNNING;
                }
                return restartFresh(true);
            }
            case NEEDS_REPLAN -> {
                discardPrecompute();
                return restartFresh(true);
            }
            case FAILED -> {
                return Status.FAILED;
            }
        }
        return Status.RUNNING;
    }

    /** Frozen context for a SEARCH — snapshot inventory + an immutable loaded-chunk view, safe to read
     *  off the tick thread. Ensure the level's snapshot exists first so the view is never the live
     *  read-through fallback (which a worker thread mustn't touch). */
    private NavContext searchContext() {
        if (player.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            com.dwinovo.animus.pathing.cache.PathCaches.ensureSnapshot(sl, player.blockPosition());
        }
        return NavContext.forSearch(player.level(), player.getInventory());
    }

    /** Off-thread when the context is frozen (the normal case); on the main thread otherwise — a
     *  context whose view is the live read-through ({@code safeForThreadedUse == false}) must NOT run
     *  on a worker. The latter is a rare safety net (e.g. no chunk snapshot yet); it returns an
     *  already-completed future so the polling code is identical. */
    private java.util.concurrent.CompletableFuture<Path> dispatch(NavContext ctx, AStarSearch s) {
        return ctx.safeForThreadedUse ? runAsync(s) : java.util.concurrent.CompletableFuture.completedFuture(runToCompletion(s));
    }

    /** Run a search to completion on the planner pool (off the tick thread). The node cap inside the
     *  search bounds it, so one {@code step} call runs the whole thing. */
    private static java.util.concurrent.CompletableFuture<Path> runAsync(AStarSearch s) {
        return com.dwinovo.animus.pathing.calc.PathPlannerPool.submit(() -> runToCompletion(s));
    }

    /** One {@code step} to the node cap; a thrown planner bug yields no path rather than wedging the
     *  companion (or, off-thread, completing the future exceptionally). */
    private static Path runToCompletion(AStarSearch s) {
        try {
            s.step(Integer.MAX_VALUE);
            return s.result();
        } catch (Throwable t) {
            com.dwinovo.animus.Constants.LOG.error("path search failed", t);
            return null;
        }
    }

    /** Live context for EXECUTION re-costing (main thread; reads current world + inventory). */
    private NavContext executionContext() {
        return NavContext.forExecution(player.level(), player.getInventory());
    }

    private void startFreshSearch() {
        NavGoal g = goalSupplier.get();
        plannedCenter = (g == null) ? null : g.center();
        if (g == null) {
            searchFuture = null;
            searchObj = null;
            return;
        }
        NavContext ctx = searchContext();
        AStarSearch s = astar.newSearch(ctx,
                BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ()),
                g, previousPathHashes);
        searchObj = s;
        searchFuture = dispatch(ctx, s);
    }

    /** Cancel and forget the in-flight main search (so a stale worker stops and its result is ignored). */
    private void cancelSearch() {
        if (searchObj != null) {
            searchObj.cancel();
            searchObj = null;
        }
        searchFuture = null;
    }

    /** Packed positions (start + every movement dest) of a path — its Favoring set. */
    private static it.unimi.dsi.fastutil.longs.LongSet pathHashes(Path p) {
        var set = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(p.movements.size() + 1);
        set.add(p.start.asLong());
        for (com.dwinovo.animus.pathing.movement.Movement m : p.movements) {
            set.add(m.dest.asLong());
        }
        return set;
    }

    private Status advanceFreshSearch() {
        if (searchFuture == null) {
            failReason = "target lost";
            return Status.FAILED;
        }
        if (!searchFuture.isDone()) {
            return Status.RUNNING;   // worker still planning — body waits (it was idle anyway)
        }
        Path path = searchFuture.getNow(null);
        searchFuture = null;
        searchObj = null;
        if (path == null || path.isEmpty()) {
            failReason = "no path to target (obstructed or out of bridging blocks)";
            return reached.getAsBoolean() ? Status.ARRIVED : Status.FAILED;
        }
        Path cut = path.staticCutoff();
        current = new PlayerPathExecutor(player, cut, speed, this::executionContext);
        previousPathHashes = pathHashes(cut);   // favor this route on the next replan
        publishViz(cut);
        return Status.RUNNING;
    }

    private Status restartFresh(boolean budgeted) {
        if (current != null) {
            current.stop();
            current = null;
        }
        cancelSearch();   // abandon any in-flight main search before dispatching a new one
        if (budgeted && replans++ >= MAX_REPLANS) {
            failReason = "gave up after " + MAX_REPLANS + " replans";
            return reached.getAsBoolean() ? Status.ARRIVED : Status.FAILED;
        }
        startFreshSearch();
        return Status.RUNNING;
    }

    private void maybePrecompute() {
        if (nextFuture != null || pendingNext != null) return;
        if (current == null || !current.isPartial()) return;
        // Baritone planAhead: start the next segment once the current one has
        // fewer than planningTickLookahead (150) ticks of travel left.
        if (current.remainingCost() > PathSettings.PLANNING_TICK_LOOKAHEAD) return;
        NavGoal g = goalSupplier.get();
        if (g == null) return;
        plannedCenter = g.center();
        NavContext ctx = searchContext();
        AStarSearch s = astar.newSearch(ctx, current.pathEnd(), g, previousPathHashes);
        nextObj = s;
        nextFuture = dispatch(ctx, s);
    }

    private void advancePrecompute() {
        if (nextFuture == null) return;
        if (!nextFuture.isDone()) return;
        Path np = nextFuture.getNow(null);
        nextFuture = null;
        nextObj = null;
        if (np != null && !np.isEmpty()) {
            Path cut = np.staticCutoff();
            pendingNext = new PlayerPathExecutor(player, cut, speed, this::executionContext);
            pendingPathForViz = cut;
            previousPathHashes = pathHashes(cut);   // the next segment becomes the favored route
        }
    }

    private void discardPrecompute() {
        if (nextObj != null) {
            nextObj.cancel();
            nextObj = null;
        }
        nextFuture = null;
        pendingPathForViz = null;
        if (pendingNext != null) {
            pendingNext.stop();
            pendingNext = null;
        }
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
        cancelSearch();
        InputDriver.halt(player);
        // Release sneak too — a pillar holds it every tick, and unlike Baritone (which
        // resets all inputs per tick) nothing clears it when the path ends, so the body
        // would stay crouched after arriving.
        player.setShiftKeyDown(false);
        PathVizPublisher.clear(player);
    }
}
