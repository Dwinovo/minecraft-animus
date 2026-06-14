package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.calc.NavGoal;
import com.dwinovo.animus.pathing.exec.BlockDigger;
import com.dwinovo.animus.pathing.exec.InputDriver;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.pathing.util.BlockScanner;
import com.dwinovo.animus.pathing.util.ScanExecutor;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code auto_mine} — a faithful port of Baritone's {@code MineProcess} to the
 * companion player body (functionally aligned; not a code copy, since Baritone
 * drives a client LocalPlayer and we drive a server fake player).
 *
 * <h2>The loop (Baritone MineProcess)</h2>
 * <ol>
 *   <li><b>knownOreLocations</b> — periodically rescan the world for target
 *       blocks ({@link BlockScanner}), and {@link #prune} every tick (drop ones
 *       mined / no longer matching / blacklisted / hazardous), sorted by
 *       distance, capped at {@link #MAX_ORES}.</li>
 *   <li><b>shaft</b> — if a target sits in our own column within reach, break it
 *       straight up immediately (no pathing), auto-switching to the best tool —
 *       Baritone's vertical-shaft mining.</li>
 *   <li><b>GoalComposite</b> — otherwise head for the whole ore field at once:
 *       one A* search over {@link NavGoal#composite} of {@link NavGoal#mine}
 *       stances, so it walks to the CLOSEST reachable ore (not greedy-nearest,
 *       which is often the walled-in one).</li>
 *   <li><b>blacklist</b> — when the path search fails, blacklist the nearest ore
 *       (presumed unreachable) and retry — Baritone's blacklistClosestOnFailure.</li>
 *   <li><b>branch mine</b> — when no ore is known, head outward holding the
 *       y-level ({@link NavGoal#runAway}) to dig fresh tunnel and expose more,
 *       bounded by {@link #MAX_BRANCH_TICKS}.</li>
 * </ol>
 */
public final class MineCompanionTask implements CompanionTask {

    private static final int RESCAN_INTERVAL = 10;     // Baritone mineGoalUpdateInterval
    private static final int MAX_ORES = 64;            // Baritone mineMaxOreLocationsCount
    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double MINE_SPEED = 1.0;
    /** Give up branch-mining after this many ticks with no ore found (~30 s). */
    private static final int MAX_BRANCH_TICKS = 600;
    /** Abandon an in-flight scan after this long so a wedged future can't stop
     *  rescanning forever (scans finish in well under a tick; this only fires if
     *  something is truly stuck). */
    private static final int SCAN_TIMEOUT_TICKS = 200;

    private final AnimusPlayer player;
    private final MineBlockTaskRecord r;
    private final List<BlockPos> knownOres = new ArrayList<>();
    private final Set<BlockPos> blacklist = new HashSet<>();
    /** Nearby dropped items to collect (Baritone droppedItemsScan), refreshed per tick. */
    private List<BlockPos> drops = List.of();

    private PlayerNav nav;
    private boolean navIsBranch;
    private BlockPos branchPoint;
    private int branchY;
    private int rescanTimer;
    private int branchTicks;
    private String doneReason = "done";
    /** In-flight background ore scan (Baritone runs its rescan off the tick thread). */
    private CompletableFuture<List<BlockScanner.Hit>> scan;
    /** Game time by which the in-flight scan must finish or be abandoned. */
    private long scanDeadline;

    // Progressive dig (Baritone mines tick-by-tick, not instabreak) — shared with
    // the path executor so all breaking reads the same.
    private final BlockDigger digger;

    public MineCompanionTask(AnimusPlayer player, MineBlockTaskRecord record) {
        this.player = player;
        this.r = record;
        this.digger = new BlockDigger(player);
    }

    @Override
    public void start() {
        rescan();
    }

    @Override
    public TaskState tick() {
        if (r.getMined() >= r.count) {
            doneReason = "mined all requested";
            return TaskState.SUCCESS;
        }

        Level level = player.level();

        // 0) Continue an in-progress dig, locked onto its block (no re-selection)
        //    until it breaks or drifts out of reach.
        BlockPos digging = digger.current();
        if (digging != null) {
            if (level.getBlockState(digging).isAir() || !withinReach(digging)) {
                digger.cancel();
            } else {
                mineProgress(digging);
                return TaskState.RUNNING;
            }
        }

        // Maintain the ore list: merge a finished background scan, prune every
        // tick (cheap — knownOres is capped at 64), and kick a fresh off-thread
        // scan every RESCAN_INTERVAL ticks (never more than one in flight).
        drainScan();
        prune();
        if (--rescanTimer <= 0) {
            rescanTimer = RESCAN_INTERVAL;
            if (scan == null) kickScan();
        }
        drops = droppedItems();

        // 1) Mine any target we can already reach + see from here (no pathing) —
        //    a tree gets mined from beside, never by digging under it.
        BlockPos reachable = reachableTarget();
        if (reachable != null) {
            stopNav();
            mineProgress(reachable);
            return TaskState.RUNNING;
        }

        // 2) Head for the ore field + nearby drops (GoalComposite), arriving when a
        //    shaft opens up; drops are collected by walking over them (native pickup).
        if (!knownOres.isEmpty() || !drops.isEmpty()) {
            branchTicks = 0;
            if (nav == null || navIsBranch) {
                stopNav();
                nav = PlayerNav.toGoal(player, this::oreFieldGoal, MINE_SPEED,
                        () -> reachableTarget() != null);
                nav.setHighlights(() -> new ArrayList<>(knownOres));   // box every known target
                navIsBranch = false;
            }
            switch (nav.tick()) {
                case RUNNING -> { return TaskState.RUNNING; }
                case ARRIVED -> { stopNav(); return TaskState.RUNNING; } // shaft handled next tick
                case FAILED -> {
                    if (!knownOres.isEmpty()) blacklistNearest();
                    stopNav();
                    return TaskState.RUNNING;
                }
            }
        }

        // 3) No ore known — branch-mine outward to expose more (bounded).
        if (branchPoint == null) {
            branchPoint = player.blockPosition();
            branchY = branchPoint.getY();
        }
        if (++branchTicks > MAX_BRANCH_TICKS) {
            doneReason = r.getMined() > 0
                    ? "mined " + r.getMined() + "/" + r.count + ", no more " + r.label + " in range"
                    : "no reachable " + r.label + " found within " + r.maxRadius + " blocks";
            return r.getMined() > 0 ? TaskState.SUCCESS : TaskState.FAILED;
        }
        if (nav == null || !navIsBranch) {
            stopNav();
            nav = PlayerNav.toGoal(player, () -> NavGoal.runAway(branchPoint, branchY),
                    MINE_SPEED, () -> false);
            nav.setHighlights(() -> new ArrayList<>(knownOres));   // (empty while branch-exploring)
            navIsBranch = true;
        }
        switch (nav.tick()) {
            case RUNNING, ARRIVED -> { return TaskState.RUNNING; }
            case FAILED -> { stopNav(); return TaskState.RUNNING; } // boxed in — rescan/retry
        }
        return TaskState.RUNNING;
    }

    // ---- goals ----

    /** GoalComposite over a mining stance per ore, plus a walk-over goal per nearby
     *  drop — one A* search heads for the closest of either. */
    private NavGoal oreFieldGoal() {
        List<NavGoal> goals = new ArrayList<>(knownOres.size() + drops.size());
        for (BlockPos ore : knownOres) {
            goals.add(NavGoal.mine(ore));
        }
        for (BlockPos drop : drops) {
            goals.add(NavGoal.near(drop, 1.0));   // walk over it; native pickup grabs it
        }
        return goals.isEmpty() ? NavGoal.exact(player.blockPosition()) : NavGoal.composite(goals);
    }

    /** Nearby dropped items (Baritone droppedItemsScan). Tight radius — mining drops
     *  land next to the body; walking over them lets native pickup collect them, and
     *  a small radius keeps the body from detouring across the cave for stray items. */
    private List<BlockPos> droppedItems() {
        AABB box = new AABB(player.blockPosition()).inflate(5.0);
        List<BlockPos> out = new ArrayList<>();
        for (ItemEntity ie : player.level().getEntitiesOfClass(ItemEntity.class, box)) {
            out.add(ie.blockPosition());
        }
        return out;
    }

    /**
     * The nearest known target the body can mine FROM WHERE IT STANDS — within
     * reach and with a clear line of sight — like a player punching a tree from
     * the side. This is what stops the companion pathing INTO an ore's column
     * (Baritone's vertical-shaft stance), which for a surface tree meant digging
     * down UNDER the trunk; here it just mines the trunk from beside. Buried ore
     * has no line of sight until the path tunnels up to it, so this returns null
     * and normal pathing exposes it first.
     */
    private BlockPos reachableTarget() {
        if (!player.onGround()) return null;
        Level level = player.level();
        Vec3 eyes = player.getEyePosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos ore : knownOres) {
            if (level.getBlockState(ore).isAir()) continue;
            double d = player.distanceToSqr(Vec3.atCenterOf(ore));
            if (d > REACH_SQR || d >= bestD) continue;
            if (!hasLineOfSight(eyes, ore)) continue;
            bestD = d;
            best = ore;
        }
        return best;
    }

    /** Clear sight line from the eyes to the target block's centre (nothing solid
     *  blocks it but the target itself). */
    private boolean hasLineOfSight(Vec3 eyes, BlockPos target) {
        BlockHitResult hit = player.level().clip(new ClipContext(
                eyes, Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    // ---- mining (progressive, tick-by-tick like Baritone / a real player) ----

    /** Advance the shared dig one tick (it switches to the best tool itself); on
     *  the tick it breaks, count it and drop it from the ore list. */
    private void mineProgress(BlockPos pos) {
        if (digger.dig(pos)) {
            r.incrementMined();
            knownOres.remove(pos);
        }
    }

    // ---- ore list maintenance ----

    /** Synchronous scan — used once at task start so there are targets immediately. */
    private void rescan() {
        mergeHits(BlockScanner.findWithin(
                player.level(), player.blockPosition(), r.maxRadius, r.targets));
    }

    /** Kick an off-thread scan: capture the loaded chunks on this (main) thread,
     *  read their section palettes on the scan thread (Baritone's WorldScanner model). */
    private void kickScan() {
        Level level = player.level();
        BlockPos center = player.blockPosition().immutable();
        List<ChunkAccess> chunks = BlockScanner.captureLoadedChunks(level, center, r.maxRadius);
        if (chunks.isEmpty()) return;
        scan = ScanExecutor.submit(
                () -> BlockScanner.scanLoaded(level, chunks, center, r.maxRadius, r.targets));
        scanDeadline = level.getGameTime() + SCAN_TIMEOUT_TICKS;
    }

    /** Merge a finished background scan into knownOres on the main thread. */
    private void drainScan() {
        if (scan == null) return;
        if (!scan.isDone()) {
            if (player.level().getGameTime() > scanDeadline) {   // wedged — drop it, re-kick later
                scan.cancel(false);
                scan = null;
            }
            return;
        }
        List<BlockScanner.Hit> hits;
        try {
            hits = scan.getNow(List.of());
        } catch (Throwable failed) {
            hits = List.of();
        }
        scan = null;
        mergeHits(hits);
    }

    /** Add fresh, non-blacklisted, non-hazardous hits to knownOres, then prune.
     *  Every candidate is re-validated here on the main thread, so a slightly
     *  stale async scan result is harmless. */
    private void mergeHits(List<BlockScanner.Hit> hits) {
        Level level = player.level();
        for (BlockScanner.Hit hit : hits) {
            BlockPos p = hit.pos().immutable();
            if (blacklist.contains(p) || knownOres.contains(p)) continue;
            if (BlockMiningProgress.fluidBreakHazard(level, p) != null) continue;
            knownOres.add(p);
        }
        prune();
    }

    private void prune() {
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        knownOres.removeIf(p ->
                level.getBlockState(p).isAir()
                        || !r.targets.contains(level.getBlockState(p).getBlock())
                        || blacklist.contains(p)
                        || BlockMiningProgress.fluidBreakHazard(level, p) != null);
        knownOres.sort(Comparator.comparingDouble(feet::distSqr));
        if (knownOres.size() > MAX_ORES) {
            knownOres.subList(MAX_ORES, knownOres.size()).clear();
        }
    }

    private void blacklistNearest() {
        BlockPos feet = player.blockPosition();
        knownOres.stream()
                .min(Comparator.comparingDouble(feet::distSqr))
                .ifPresent(p -> {
                    blacklist.add(p);
                    knownOres.remove(p);
                });
    }

    private boolean withinReach(BlockPos pos) {
        return player.distanceToSqr(Vec3.atCenterOf(pos)) <= REACH_SQR;
    }

    private void stopNav() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
        navIsBranch = false;
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        stopNav();
        digger.cancel();
        if (scan != null) {
            scan.cancel(false);
            scan = null;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("mined", r.getMined());
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(
                    "mined " + r.getMined() + "/" + r.count + " " + r.label + " (" + doneReason + ")", data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out after mining " + r.getMined() + "/" + r.count + " " + r.label, true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "interrupted after mining " + r.getMined() + "/" + r.count + " " + r.label, false, true, data);
            case FAILED -> TaskResult.fail(doneReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
