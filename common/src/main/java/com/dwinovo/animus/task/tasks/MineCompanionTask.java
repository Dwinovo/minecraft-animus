package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.calc.NavGoal;
import com.dwinovo.animus.pathing.exec.InputDriver;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.pathing.util.BlockScanner;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // Progressive dig state (Baritone mines tick-by-tick, not instabreak).
    private BlockPos digPos;
    private int digTicks;
    private int digTotal;
    private int swingCd;
    private int lastStage = -1;

    public MineCompanionTask(AnimusPlayer player, MineBlockTaskRecord record) {
        this.player = player;
        this.r = record;
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
        if (digPos != null) {
            if (level.getBlockState(digPos).isAir() || !withinReach(digPos)) {
                clearDigProgress();
            } else {
                mineTick();
                return TaskState.RUNNING;
            }
        }

        // Maintain the ore list: full rescan periodically, prune every tick.
        if (--rescanTimer <= 0) {
            rescanTimer = RESCAN_INTERVAL;
            rescan();
        } else {
            prune();
        }
        drops = droppedItems();

        // 1) Shaft: a target in our own column within reach → start breaking it
        //    (tick-by-tick), no pathing.
        BlockPos shaft = shaftTarget();
        if (shaft != null) {
            stopNav();
            startDig(shaft);
            mineTick();
            return TaskState.RUNNING;
        }

        // 2) Head for the ore field + nearby drops (GoalComposite), arriving when a
        //    shaft opens up; drops are collected by walking over them (native pickup).
        if (!knownOres.isEmpty() || !drops.isEmpty()) {
            branchTicks = 0;
            if (nav == null || navIsBranch) {
                stopNav();
                nav = PlayerNav.toGoal(player, this::oreFieldGoal, MINE_SPEED,
                        () -> shaftTarget() != null);
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

    /** A known ore in our own column, at/above feet, within reach, still solid. */
    private BlockPos shaftTarget() {
        if (!player.onGround()) return null;
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos ore : knownOres) {
            if (ore.getX() != feet.getX() || ore.getZ() != feet.getZ()) continue;
            if (ore.getY() < feet.getY()) continue;
            if (level.getBlockState(ore).isAir()) continue;
            if (!withinReach(ore)) continue;
            double d = ore.distSqr(feet.above());
            if (d < bestD) {
                bestD = d;
                best = ore;
            }
        }
        return best;
    }

    // ---- mining (progressive, tick-by-tick like Baritone / a real player) ----

    /** Begin breaking {@code pos}: switch to the best tool and compute how many
     *  ticks the dig will take from the real vanilla mining speed. */
    private void startDig(BlockPos pos) {
        clearDigProgress();
        switchToBestTool(player.level().getBlockState(pos));
        digPos = pos.immutable();
        digTicks = 0;
        swingCd = 0;
        lastStage = -1;
        digTotal = vanillaMiningTicks(pos);
    }

    /** Advance the current dig one tick: face it, swing, push the crack overlay,
     *  and break it once enough ticks have elapsed. */
    private void mineTick() {
        Level level = player.level();
        InputDriver.halt(player);
        InputDriver.lookAt(player, Vec3.atCenterOf(digPos));
        if (swingCd-- <= 0) {
            player.swing(InteractionHand.MAIN_HAND);
            swingCd = 5;                       // vanilla swings ~every 6 ticks while mining
        }
        digTicks++;
        int stage = Math.min(9, (int) ((digTicks / (float) digTotal) * 10.0f));
        if (stage != lastStage) {                 // a real player only re-broadcasts on stage change
            level.destroyBlockProgress(player.getId(), digPos, stage);
            lastStage = stage;
        }
        if (digTicks >= digTotal) {
            BlockPos done = digPos;
            player.gameMode.destroyBlock(done);
            if (level.getBlockState(done).isAir()) {
                r.incrementMined();
                knownOres.remove(done);
            }
            clearDigProgress();
        }
    }

    /** Real vanilla dig duration in ticks: getDestroyProgress is the per-tick
     *  fraction (tool/enchant/haste/in-water/airborne all folded in), so the dig
     *  takes ceil(1 / fraction) ticks — exactly what a player would experience. */
    private int vanillaMiningTicks(BlockPos pos) {
        Level level = player.level();
        float perTick = level.getBlockState(pos).getDestroyProgress(player, level, pos);
        if (perTick <= 0.0f) return 1;
        return Math.max(1, (int) Math.ceil(1.0f / perTick));
    }

    /** Clear the crack overlay and forget the in-progress dig. */
    private void clearDigProgress() {
        if (digPos != null) {
            player.level().destroyBlockProgress(player.getId(), digPos, -1);
            digPos = null;
        }
    }

    /** Baritone switchToBestToolFor / ToolSet.getBestSlot: select the hotbar slot
     *  whose item mines {@code state} fastest. */
    private void switchToBestTool(BlockState state) {
        Inventory inv = player.getInventory();
        int best = inv.getSelectedSlot();
        float bestSpeed = inv.getItem(best).getDestroySpeed(state);
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            float s = inv.getItem(i).getDestroySpeed(state);
            if (s > bestSpeed) {
                bestSpeed = s;
                best = i;
            }
        }
        inv.setSelectedSlot(best);
    }

    // ---- ore list maintenance ----

    private void rescan() {
        Level level = player.level();
        for (BlockScanner.Hit hit : BlockScanner.findWithin(
                level, player.blockPosition(), r.maxRadius, r.targets)) {
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
        clearDigProgress();
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
