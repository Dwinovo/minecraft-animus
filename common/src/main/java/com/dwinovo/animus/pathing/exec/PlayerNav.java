package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.calc.AStar;
import com.dwinovo.animus.pathing.calc.AStarSearch;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.Path;
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
 */
public final class PlayerNav {

    public enum Status { RUNNING, ARRIVED, FAILED }

    private static final int NODES_PER_TICK = AStar.DEFAULT_NODES_PER_TICK;
    private static final int MAX_REPLANS = 40;
    private static final double GOAL_MOVED_SQR = 4.0;

    private final AnimusPlayer player;
    private final Supplier<BlockPos> goalSupplier;
    private final double speed;
    private final BooleanSupplier reached;
    private final AStar astar = new AStar();

    private BlockPos plannedGoal;
    private PlayerPathExecutor current;

    private AStarSearch search;
    private AStarSearch nextSearch;
    private PlayerPathExecutor pendingNext;

    private int replans = 0;
    private String failReason = "target unreachable";

    public PlayerNav(AnimusPlayer player, BlockPos goal, double speed, BooleanSupplier reached) {
        this(player, () -> goal, speed, reached);
    }

    public PlayerNav(AnimusPlayer player, Supplier<BlockPos> goalSupplier, double speed,
                     BooleanSupplier reached) {
        this.player = player;
        this.goalSupplier = goalSupplier;
        this.speed = speed;
        this.reached = reached;
        startFreshSearch();
    }

    public Status tick() {
        if (reached.getAsBoolean()) return Status.ARRIVED;

        if (current == null) {
            return advanceFreshSearch();
        }

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
                replans = 0;
                if (reached.getAsBoolean()) return Status.ARRIVED;
                if (pendingNext != null) {
                    current.stop();
                    current = pendingNext;
                    pendingNext = null;
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
        return new NavContext(player.level(), player.getMainHandItem(), player.getInventory());
    }

    private void startFreshSearch() {
        BlockPos g = goalSupplier.get();
        plannedGoal = g;
        search = (g == null) ? null : astar.newSearch(freshContext(), player.blockPosition(), g);
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
        current = new PlayerPathExecutor(player, path.staticCutoff(), speed, this::freshContext);
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
        BlockPos g = goalSupplier.get();
        if (g == null) return;
        plannedGoal = g;
        nextSearch = astar.newSearch(freshContext(), current.pathEnd(), g);
    }

    private void advancePrecompute() {
        if (nextSearch == null) return;
        if (nextSearch.step(NODES_PER_TICK) == AStarSearch.State.COMPUTING) return;
        Path np = nextSearch.result();
        nextSearch = null;
        if (np != null && !np.isEmpty()) {
            pendingNext = new PlayerPathExecutor(player, np.staticCutoff(), speed, this::freshContext);
        }
    }

    private void discardPrecompute() {
        nextSearch = null;
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
    }
}
