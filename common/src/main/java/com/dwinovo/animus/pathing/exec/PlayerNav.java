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

    private static final int NODES_PER_TICK = AStar.DEFAULT_NODES_PER_TICK;
    private static final int MAX_REPLANS = 40;
    private static final double GOAL_MOVED_SQR = 4.0;

    private final AnimusPlayer player;
    private final Supplier<NavGoal> goalSupplier;
    private final double speed;
    private final BooleanSupplier reached;
    private final AStar astar = new AStar();

    private BlockPos plannedCenter;
    private PlayerPathExecutor current;

    private AStarSearch search;
    private AStarSearch nextSearch;
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

    private NavContext freshContext() {
        return new NavContext(player.level(), player.getInventory());
    }

    private void startFreshSearch() {
        NavGoal g = goalSupplier.get();
        plannedCenter = (g == null) ? null : g.center();
        search = (g == null) ? null
                : astar.newSearch(freshContext(),
                        BlockHelper.playerFeet(player.level(), player.getX(), player.getY(), player.getZ()),
                        g, previousPathHashes);
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
        if (search == null) {
            failReason = "target lost";
            return Status.FAILED;
        }
        if (search.step(NODES_PER_TICK) == AStarSearch.State.COMPUTING) {
            return Status.RUNNING;
        }
        Path path = search.result();
        search = null;
        if (path == null || path.isEmpty()) {
            failReason = "no path to target (obstructed or out of bridging blocks)";
            return reached.getAsBoolean() ? Status.ARRIVED : Status.FAILED;
        }
        Path cut = path.staticCutoff();
        current = new PlayerPathExecutor(player, cut, speed, this::freshContext);
        previousPathHashes = pathHashes(cut);   // favor this route on the next replan
        publishViz(cut);
        return Status.RUNNING;
    }

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

    private void maybePrecompute() {
        if (nextSearch != null || pendingNext != null) return;
        if (current == null || !current.isPartial()) return;
        // Baritone planAhead: start the next segment once the current one has
        // fewer than planningTickLookahead (150) ticks of travel left.
        if (current.remainingCost() > PathSettings.PLANNING_TICK_LOOKAHEAD) return;
        NavGoal g = goalSupplier.get();
        if (g == null) return;
        plannedCenter = g.center();
        nextSearch = astar.newSearch(freshContext(), current.pathEnd(), g, previousPathHashes);
    }

    private void advancePrecompute() {
        if (nextSearch == null) return;
        if (nextSearch.step(NODES_PER_TICK) == AStarSearch.State.COMPUTING) return;
        Path np = nextSearch.result();
        nextSearch = null;
        if (np != null && !np.isEmpty()) {
            Path cut = np.staticCutoff();
            pendingNext = new PlayerPathExecutor(player, cut, speed, this::freshContext);
            pendingPathForViz = cut;
            previousPathHashes = pathHashes(cut);   // the next segment becomes the favored route
        }
    }

    private void discardPrecompute() {
        nextSearch = null;
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
        search = null;
        InputDriver.halt(player);
        // Release sneak too — a pillar holds it every tick, and unlike Baritone (which
        // resets all inputs per tick) nothing clears it when the path ends, so the body
        // would stay crouched after arriving.
        player.setShiftKeyDown(false);
        PathVizPublisher.clear(player);
    }
}
