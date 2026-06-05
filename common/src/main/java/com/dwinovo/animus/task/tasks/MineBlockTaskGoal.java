package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.calc.AStar;
import com.dwinovo.animus.pathing.calc.AStarSearch;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.Path;
import com.dwinovo.animus.pathing.exec.PathExecutor;
import com.dwinovo.animus.pathing.util.BlockScanner;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Intent-level miner for {@link MineBlockTaskRecord}: "gather N of these block
 * types — find them, walk there, dig them, repeat." The LLM only says what and
 * how many; this goal owns the entire loop, reusing the three building blocks
 * already in the mod:
 *
 * <ul>
 *   <li>{@link BlockScanner} — palette-short-circuited spherical search for the
 *       nearest remaining target (re-run as the deposit depletes).</li>
 *   <li>{@link AStar} + {@link PathExecutor} — the time-sliced, terrain-modifying
 *       pathfinder: it bridges gaps, pillars up, and mines through obstructions
 *       to reach the block, exactly like {@code move_to}.</li>
 *   <li>{@link BlockMiningProgress} — the vanilla-accurate dig (swing, crack
 *       overlay, drops routed into the entity inventory).</li>
 * </ul>
 *
 * <h2>State machine (per tick)</h2>
 * <pre>
 *   SCAN  → find nearest target within the current radius; auto-expand the
 *           radius up to maxRadius; none left → DONE (partial success).
 *   PATH  → time-sliced A* to the block, then drive the PathExecutor until
 *           within mining reach; unreachable/stuck → skip this block, re-SCAN.
 *   MINE  → BlockMiningProgress until broken; count++; reached count → DONE;
 *           else → SCAN for the next one.
 * </pre>
 *
 * <h2>Partial success</h2>
 * If the radius is exhausted before {@code count} is met, the task still
 * succeeds with {@code mined < count} and a reason — the LLM gets the real
 * number and decides whether to relocate or settle. A genuinely zero-yield
 * search (nothing found at all) fails.
 */
public final class MineBlockTaskGoal extends LlmTaskGoal<MineBlockTaskRecord> {

    private enum Phase { SCAN, PATH, MINE }

    /** Node budget per tick handed to the in-flight A* search (mirrors MoveToTaskGoal). */
    private static final int NODES_PER_TICK = AStar.DEFAULT_NODES_PER_TICK;
    /** Initial search radius before auto-expansion. */
    private static final int INITIAL_RADIUS = 16;
    /** Radius growth step when the current radius is exhausted. */
    private static final int RADIUS_STEP = 16;
    /** Walking speed toward a target block. */
    private static final double MINE_WALK_SPEED = 1.0;
    /** Replans allowed per individual block before we give up on it and re-scan. */
    private static final int MAX_REPLANS_PER_BLOCK = 12;

    private final AStar astar = new AStar();
    private final BlockMiningProgress mining;

    private Phase phase = Phase.SCAN;
    private int currentRadius = INITIAL_RADIUS;

    // Active target block + the route to it.
    private BlockPos targetBlock;
    private AStarSearch search;
    private NavContext planningCtx;
    private PathExecutor executor;
    private int replansForBlock = 0;
    private boolean miningStarted = false;

    /** Targets we gave up on (unreachable / un-line-of-sight) so SCAN won't re-pick them. */
    private final java.util.Set<BlockPos> skipped = new java.util.HashSet<>();
    private String doneReason = "done";

    public MineBlockTaskGoal(AnimusEntity entity) {
        super(entity, MineBlockTaskRecord.TOOL_NAME, MineBlockTaskRecord.class);
        this.mining = new BlockMiningProgress(entity);
    }

    @Override
    protected void onStart(MineBlockTaskRecord r) {
        // Harvest gate (fast-fail): if the CURRENTLY HELD tool can't harvest ANY
        // requested target type, refuse before walking or digging and tell the
        // model what to equip. Breaking a block the tool can't harvest yields
        // nothing — a bare "0 mined" teaches the model nothing; the requirement
        // string does. We deliberately do NOT auto-equip a better tool from the
        // bag: silently swapping would teach the model the wrong lesson (e.g.
        // "a wooden pickaxe mined iron ore"). The model must equip the right tool
        // itself, so its mental model of tool tiers stays correct.
        // (Mixed-tier calls where SOME types are harvestable proceed; the
        // per-target gate in tickScan skips the un-harvestable ones.)
        String blocked = allTargetsHarvestBlocked(r);
        if (blocked != null) {
            doneReason = blocked;
            r.setState(TaskState.FAILED);
            return;
        }
        this.currentRadius = Math.min(INITIAL_RADIUS, r.maxRadius);
        this.phase = Phase.SCAN;
    }

    /**
     * Guidance string if NONE of the requested target types can be harvested
     * with the currently-held tool; {@code null} if at least one can. Checks each
     * block's default state — harvest tier is state-independent for the
     * ores/stone these calls target.
     */
    private String allTargetsHarvestBlocked(MineBlockTaskRecord r) {
        String lastReason = null;
        for (Block b : r.targets) {
            String reason = BlockMiningProgress.harvestRequirement(entity, b.defaultBlockState());
            if (reason == null) return null;   // at least one harvestable → allow the task
            lastReason = reason;
        }
        return lastReason;
    }

    @Override
    protected void onTick(MineBlockTaskRecord r) {
        switch (phase) {
            case SCAN -> tickScan(r);
            case PATH -> tickPath(r);
            case MINE -> tickMine(r);
        }
    }

    // ---- SCAN: locate the nearest remaining target, auto-expanding radius ----

    private void tickScan(MineBlockTaskRecord r) {
        if (r.getMined() >= r.count) {
            doneReason = "mined all requested";
            r.setState(TaskState.SUCCESS);
            return;
        }
        Level level = entity.level();
        BlockPos center = entity.blockPosition();
        BlockScanner.Hit hit = nearestUnskipped(level, center, r);

        if (hit == null) {
            // Nothing in the current radius — grow and retry next tick, until cap.
            if (currentRadius < r.maxRadius) {
                currentRadius = Math.min(currentRadius + RADIUS_STEP, r.maxRadius);
                return;
            }
            // Cap reached, deposit dry. Partial success if we got anything.
            doneReason = r.getMined() > 0
                    ? "only " + r.getMined() + "/" + r.count + " found within " + r.maxRadius + " blocks"
                    : "no " + r.label + " found within " + r.maxRadius + " blocks";
            r.setState(r.getMined() > 0 ? TaskState.SUCCESS : TaskState.FAILED);
            return;
        }

        targetBlock = hit.pos();
        replansForBlock = 0;
        // Per-target harvest gate: in a mixed block_ids call (some types need a
        // better tool than others), skip the ones the held tool can't harvest so
        // we don't waste a dig, while still mining the types we can. onStart
        // already fast-failed the all-blocked case, so this never starves.
        if (BlockMiningProgress.harvestRequirement(entity, level.getBlockState(targetBlock)) != null) {
            skipped.add(targetBlock.immutable());
            return;   // re-scan next tick for a harvestable target
        }
        if (withinReach(targetBlock)) {
            beginMining(r);
        } else {
            startPlanning();
            phase = Phase.PATH;
        }
    }

    // ---- PATH: time-sliced A* to the block, then drive the executor ----

    private void tickPath(MineBlockTaskRecord r) {
        if (withinReach(targetBlock)) {
            stopExecutor();
            beginMining(r);
            return;
        }
        // Still planning this tick?
        if (search != null) {
            if (search.step(NODES_PER_TICK) == AStarSearch.State.COMPUTING) {
                return;
            }
            Path path = search.result();
            search = null;
            planningCtx = null;
            if (path == null || path.isEmpty()) {
                skipBlock();      // can't route to it — abandon, re-scan
                return;
            }
            executor = new PathExecutor(entity, path, MINE_WALK_SPEED);
            return;
        }
        // Executing the path.
        if (executor == null) {
            skipBlock();
            return;
        }
        switch (executor.tick()) {
            case RUNNING -> { /* keep walking */ }
            case ARRIVED, NEEDS_REPLAN -> {
                if (withinReach(targetBlock)) {
                    stopExecutor();
                    beginMining(r);
                } else if (!startPlanning()) {
                    skipBlock();
                }
            }
            case FAILED -> skipBlock();
        }
    }

    // ---- MINE: dig the block, route drops, count it ----

    private void tickMine(MineBlockTaskRecord r) {
        if (!withinReach(targetBlock)) {
            // Got knocked away mid-dig — re-approach.
            mining.cleanup(targetBlock);
            miningStarted = false;
            phase = Phase.SCAN;
            return;
        }
        switch (mining.tick(targetBlock)) {
            case IN_PROGRESS -> { /* keep swinging */ }
            case COMPLETED, FAILED_BLOCK_GONE -> {
                mining.cleanup(targetBlock);
                miningStarted = false;
                r.incrementMined();          // updates debug overlay via describe()
                entity.setDebugTask(r.describe());
                phase = Phase.SCAN;          // look for the next one
            }
            case FAILED_BLOCK_CHANGED -> {
                mining.cleanup(targetBlock);
                miningStarted = false;
                phase = Phase.SCAN;          // re-evaluate
            }
            case FAILED_OUT_OF_REACH -> {
                // Knocked out of range mid-dig. Re-approach via the pathfinder
                // rather than spinning here.
                mining.cleanup(targetBlock);
                miningStarted = false;
                if (!startPlanning()) {
                    skipBlock();
                } else {
                    phase = Phase.PATH;
                }
            }
        }
    }

    // ---- helpers ----

    private void beginMining(MineBlockTaskRecord r) {
        if (BlockMiningProgress.checkMineable(entity.level(), targetBlock) != null) {
            phase = Phase.SCAN;              // became air/unbreakable — skip
            return;
        }
        if (!mining.tryStart(targetBlock)) {
            phase = Phase.SCAN;
            return;
        }
        miningStarted = true;
        phase = Phase.MINE;
    }

    /** Begin/replan a time-sliced search to the current target. False when out of replan budget. */
    private boolean startPlanning() {
        stopExecutor();
        if (replansForBlock++ >= MAX_REPLANS_PER_BLOCK) {
            return false;
        }
        planningCtx = new NavContext(entity);
        search = astar.newSearch(planningCtx, entity.blockPosition(), targetBlock);
        return true;
    }

    /**
     * Abandon the current target and scan for another. The block is added to a
     * skip set so SCAN won't immediately re-pick the same unreachable one and
     * spin — without this, a deposit behind unbreakable cover (or that A* can't
     * route to) would loop until the deadline.
     */
    private void skipBlock() {
        stopExecutor();
        search = null;
        planningCtx = null;
        if (targetBlock != null) skipped.add(targetBlock.immutable());
        phase = Phase.SCAN;
    }

    private void stopExecutor() {
        if (executor != null) {
            executor.stop();
            executor = null;
        }
    }

    /**
     * Reachable = standing stably within dig radius AND with a clear line of
     * sight (no mining through walls).
     *
     * <p>The {@link net.minecraft.world.entity.Entity#onGround() onGround} guard
     * is what stops the "jump-mine" death loop: for a target overhead the entity
     * pillars up, and at the apex of each pillar jump it would momentarily fall
     * inside the 4.5-block radius. Without this guard, that apex flicker aborts
     * the in-progress pillar (it kills the executor and starts mining), the
     * entity drops back down out of reach, re-approaches, jumps again — forever.
     * Requiring solid footing means we only commit to mining once the pillar has
     * actually completed and the entity is standing on the new block.
     */
    private boolean withinReach(BlockPos pos) {
        return entity.onGround()
                && entity.distanceToSqr(Vec3.atCenterOf(pos)) <= BlockMiningProgress.REACH_SQR
                && BlockMiningProgress.hasLineOfSight(entity, pos);
    }

    /** Nearest target within the current radius that isn't in the skip set, or null. */
    private BlockScanner.Hit nearestUnskipped(Level level, BlockPos center, MineBlockTaskRecord r) {
        for (BlockScanner.Hit hit : BlockScanner.findWithin(level, center, currentRadius, r.targets)) {
            if (!skipped.contains(hit.pos())) {
                return hit;
            }
        }
        return null;
    }

    @Override
    protected TaskResult buildResult(MineBlockTaskRecord r, TaskState finalState) {
        stopExecutor();
        if (miningStarted && targetBlock != null) {
            mining.cleanup(targetBlock);
        }
        entity.getNavigation().stop();

        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("mined", r.getMined());
        data.put("radius_searched", currentRadius);

        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(
                    "mined " + r.getMined() + "/" + r.count + " " + r.label + " (" + doneReason + ")",
                    data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out after mining " + r.getMined() + "/" + r.count + " " + r.label,
                    true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "interrupted after mining " + r.getMined() + "/" + r.count + " " + r.label,
                    false, true, data);
            case FAILED -> TaskResult.fail(doneReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
