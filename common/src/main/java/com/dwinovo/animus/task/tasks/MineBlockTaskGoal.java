package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.exec.Navigator;
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
 * how many; this goal owns the loop, reusing the mod's building blocks:
 *
 * <ul>
 *   <li>{@link BlockScanner} — palette-short-circuited spherical search for the
 *       nearest remaining target (re-run as the deposit depletes).</li>
 *   <li>{@link Navigator} — the shared time-sliced, terrain-modifying pathfinder
 *       driver: it bridges gaps, pillars up, and mines through obstructions to
 *       reach the block, planning while it walks, exactly like {@code move_to}.</li>
 *   <li>{@link BlockMiningProgress} — the vanilla-accurate dig (swing, crack
 *       overlay, drops routed into the entity inventory).</li>
 * </ul>
 *
 * <h2>State machine (per tick)</h2>
 * <pre>
 *   SCAN  → find nearest target within the current radius; auto-expand the
 *           radius up to maxRadius; none left → DONE (partial success).
 *   PATH  → drive the Navigator toward the block until within mining reach;
 *           unreachable → skip this block, re-SCAN.
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

    /** Initial search radius before auto-expansion. */
    private static final int INITIAL_RADIUS = 16;
    /** Radius growth step when the current radius is exhausted. */
    private static final int RADIUS_STEP = 16;
    /** Walking speed toward a target block. */
    private static final double MINE_WALK_SPEED = 1.0;

    private final BlockMiningProgress mining;

    private Phase phase = Phase.SCAN;
    private int currentRadius = INITIAL_RADIUS;

    // Active target block + the route to it.
    private BlockPos targetBlock;
    private Navigator nav;
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
            String fluidNote = skippedFluid > 0
                    ? " (" + skippedFluid + " more sit against water or lava — I won't "
                            + "flood or lava-bathe the dig; drain the water with "
                            + "use_item(bucket), or relocate away from lava, then retry)"
                    : "";
            doneReason = (r.getMined() > 0
                    ? "only " + r.getMined() + "/" + r.count + " found within " + r.maxRadius + " blocks"
                    : "no reachable " + r.label + " found within " + r.maxRadius + " blocks")
                    + fluidNote;
            r.setState(r.getMined() > 0 ? TaskState.SUCCESS : TaskState.FAILED);
            return;
        }

        targetBlock = hit.pos();
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
            beginNav();
            phase = Phase.PATH;
        }
    }

    // ---- PATH: drive the Navigator until within mining reach ----

    private void tickPath(MineBlockTaskRecord r) {
        if (nav == null) {
            skipBlock();
            return;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* keep walking */ }
            // ARRIVED == our reached-predicate (withinReach) is true.
            case ARRIVED -> {
                stopNav();
                beginMining(r);
            }
            case FAILED -> skipBlock();   // can't route to it — abandon, re-scan
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
                beginNav();
                phase = Phase.PATH;
            }
        }
    }

    // ---- helpers ----

    private void beginMining(MineBlockTaskRecord r) {
        if (BlockMiningProgress.checkMineable(entity.level(), targetBlock) != null) {
            phase = Phase.SCAN;              // became air/unbreakable — skip
            return;
        }
        // Stance discipline (Altoclef-style): never dig the block under your
        // own feet — step one cell aside and dig it from there. No adjacent
        // footing (1×1 pillar top, bridge end) → skip the target entirely;
        // the explicit break_block tool remains the deliberate way down.
        if (standingOn(targetBlock)) {
            BlockPos side = adjacentStance();
            if (side == null) {
                skipBlock();
                return;
            }
            stopNav();
            nav = new Navigator(entity, side, MINE_WALK_SPEED,
                    () -> withinReach(targetBlock) && !standingOn(targetBlock));
            phase = Phase.PATH;
            return;
        }
        if (!mining.tryStart(targetBlock)) {
            phase = Phase.SCAN;
            return;
        }
        miningStarted = true;
        phase = Phase.MINE;
    }

    private boolean standingOn(BlockPos pos) {
        return entity.blockPosition().below().equals(pos);
    }

    /** A standable cell horizontally adjacent to the feet, or null. */
    private BlockPos adjacentStance() {
        BlockPos feet = entity.blockPosition();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos cand = feet.relative(d);
            if (com.dwinovo.animus.pathing.util.BlockHelper.isStandable(entity.level(), cand)) {
                return cand;
            }
        }
        return null;
    }

    /** Begin driving the shared Navigator toward the current target block. */
    private void beginNav() {
        stopNav();
        nav = new Navigator(entity, targetBlock, MINE_WALK_SPEED, () -> withinReach(targetBlock));
    }

    /**
     * Abandon the current target and scan for another. The block is added to a
     * skip set so SCAN won't immediately re-pick the same unreachable one and
     * spin — without this, a deposit behind unbreakable cover (or that the
     * Navigator can't route to) would loop until the deadline.
     */
    private void skipBlock() {
        stopNav();
        if (targetBlock != null) skipped.add(targetBlock.immutable());
        phase = Phase.SCAN;
    }

    private void stopNav() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
    }

    /**
     * Reachable = standing stably within dig radius AND with a clear line of
     * sight (no mining through walls).
     *
     * <p>The {@link net.minecraft.world.entity.Entity#onGround() onGround} guard
     * keeps us from committing to a dig while airborne mid-pillar: only start
     * mining once the entity is standing stably on the new block. (With the
     * re-localization executor a pillar no longer apex-flickers, but mining
     * while airborne is still wrong, so the guard stays.)
     */
    private boolean withinReach(BlockPos pos) {
        return entity.onGround()
                && entity.distanceToSqr(Vec3.atCenterOf(pos)) <= BlockMiningProgress.REACH_SQR
                && BlockMiningProgress.hasLineOfSight(entity, pos);
    }

    /** Nearest target within the current radius that isn't in the skip set, or null. */
    /**
     * Best remaining target by {@link MiningEconomics#score} (distance plus a
     * per-block penalty for depth below the feet — lateral deposits beat
     * shaft-digging when both exist), excluding skip-listed positions, the
     * pet's own placed scaffolding (the bridge it walked in on), and
     * walkway-looking blocks floating over liquid at its own floor level
     * (cold-start guard for bridges the ledger no longer remembers).
     */
    /** Fluid-adjacent candidates skipped in the last scan — for the teaching message. */
    private int skippedFluid;

    private BlockScanner.Hit nearestUnskipped(Level level, BlockPos center, MineBlockTaskRecord r) {
        BlockScanner.Hit best = null;
        double bestScore = Double.MAX_VALUE;
        skippedFluid = 0;
        for (BlockScanner.Hit hit : BlockScanner.findWithin(level, center, currentRadius, r.targets)) {
            // findWithin is distance-ascending and the penalty is non-negative,
            // so once raw distance exceeds the best score nothing can win.
            if (hit.distance() >= bestScore) break;
            if (skipped.contains(hit.pos())) continue;
            if (entity.scaffoldLedger().isOwnScaffold(hit.pos(), hit.state().getBlock())) continue;
            if (looksLikeOwnWalkway(level, center, hit.pos())) continue;
            if (BlockMiningProgress.fluidBreakHazard(level, hit.pos()) != null) {
                skippedFluid++;   // breaking it would flood/burn — counted for the teach
                continue;
            }
            double score = MiningEconomics.score(hit.distance(), center.getY(), hit.pos().getY());
            if (score < bestScore) {
                bestScore = score;
                best = hit;
            }
        }
        return best;
    }

    /**
     * Cold-start bridge heuristic (the ledger forgets across restarts): a
     * target sitting in the pet's own floor plane with liquid directly
     * beneath is in all likelihood the walkway it is standing on — mining it
     * cuts the way home and ends in a swim.
     */
    private static boolean looksLikeOwnWalkway(Level level, BlockPos feet, BlockPos target) {
        return target.getY() == feet.getY() - 1
                && !level.getBlockState(target.below()).getFluidState().isEmpty();
    }

    @Override
    protected TaskResult buildResult(MineBlockTaskRecord r, TaskState finalState) {
        stopNav();
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
