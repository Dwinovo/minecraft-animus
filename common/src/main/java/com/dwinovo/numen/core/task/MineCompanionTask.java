package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.exec.BlockDigger;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.pathing.util.BlockScanner;
import com.dwinovo.numen.core.pathing.util.ScanExecutor;
import com.dwinovo.numen.core.pathing.viz.PathVizPublisher;
import com.dwinovo.numen.core.task.CompanionTask;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
    /**
     * Baritone {@code MineProcess.updateGoal}: with {@code exploreForBlocks} (default
     * FALSE) and {@code legitMine} (default FALSE) both off, "no ore known" returns
     * null → the process CANCELS — it does NOT wander off hunting for more. Only with
     * {@code exploreForBlocks} does it branch-mine outward. We mirror the default:
     * stop. Flip this to re-enable Baritone's opt-in explore mode (the branch-mine
     * below). */
    private static final boolean EXPLORE_FOR_BLOCKS = false;
    /** Abandon an in-flight scan after this long so a wedged future can't stop
     *  rescanning forever (scans finish in well under a tick; this only fires if
     *  something is truly stuck). */
    private static final int SCAN_TIMEOUT_TICKS = 200;

    private final NumenPlayer player;
    private final MineBlockTaskRecord r;
    private final List<BlockPos> knownOres = new ArrayList<>();
    private final Set<BlockPos> blacklist = new HashSet<>();
    /** Items the target blocks drop (loot-table simulated, Baritone BlockOptionalMeta.drops). The
     *  count is over THESE in the inventory, not blocks broken — redstone_ore yields ~4 redstone. */
    private Set<Item> dropItems = Set.of();
    /** Matching items already in the inventory when the task began — the count is the DELTA above this
     *  (companion semantics: "gather N more", not Baritone's absolute "have N"). */
    private int baseline;
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

    public MineCompanionTask(NumenPlayer player, MineBlockTaskRecord record) {
        this.player = player;
        this.r = record;
        this.digger = new BlockDigger(player);
    }

    @Override
    public void start() {
        // Fail fast if NO requested target is harvestable with the current inventory — mining it
        // would destroy the block for no drop. Same gate as break_block / the cost model
        // (BlockHelper.canHarvest, whole-inventory). prune() then drops any individual unharvestable
        // cell, so a mixed request (e.g. coal we can mine + diamond we can't) still works.
        boolean anyHarvestable = r.targets.stream().anyMatch(
                b -> BlockHelper.canHarvest(player.getInventory(), b.defaultBlockState()));
        if (!anyHarvestable) {
            doneReason = "can't harvest " + r.label + " with the current tools — mining it would"
                    + " destroy it without any drop. Equip a suitable tool (e.g. a pickaxe) first.";
            r.setState(TaskState.FAILED);
            return;
        }
        // Count toward `count` by ITEMS gathered (Baritone), not blocks broken: resolve what these
        // blocks drop, and snapshot how many we already hold so the tally is the delta above it.
        dropItems = computeDropItems();
        baseline = inventoryMatch();
        rescan();
    }

    @Override
    public TaskState tick() {
        int gathered = Math.max(0, inventoryMatch() - baseline);   // matching items gained so far
        r.setMined(gathered);
        if (gathered >= r.count) {
            doneReason = "gathered all requested";
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
            // Baritone keeps the goal box rendered while it mines in place (the path
            // executor is paused, but drawGoal(behavior.getGoal()) still runs). stopNav
            // cleared the overlay, so re-publish the ore field boxes — otherwise the
            // boxes vanish the instant shaft-mining starts (the "boxes disappear after
            // two logs" bug). No path line while shaft-mining, just the goal.
            PathVizPublisher.publishTargets(player, new ArrayList<>(knownOres));
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

        // 3) No ore known and nothing dropped nearby. Baritone's default stops here
        //    (cancel); only its opt-in explore mode branch-mines. Match the default:
        //    finish with whatever we gathered (the tool's contract: "fewer than count
        //    in range still succeeds"), rather than running off across the world.
        if (!EXPLORE_FOR_BLOCKS) {
            doneReason = r.getMined() > 0
                    ? "gathered " + r.getMined() + "/" + r.count + ", no more " + r.label + " in range"
                    : "no reachable " + r.label + " found within " + r.maxRadius + " blocks";
            return r.getMined() > 0 ? TaskState.SUCCESS : TaskState.FAILED;
        }

        // 3b) Opt-in explore (Baritone exploreForBlocks) — branch-mine outward (bounded).
        if (branchPoint == null) {
            branchPoint = player.blockPosition();
            branchY = branchPoint.getY();
        }
        if (++branchTicks > MAX_BRANCH_TICKS) {
            doneReason = r.getMined() > 0
                    ? "gathered " + r.getMined() + "/" + r.count + ", no more " + r.label + " in range"
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
            goals.add(coalesce(ore));
        }
        for (BlockPos drop : drops) {
            goals.add(NavGoal.near(drop, 1.0));   // walk over it; native pickup grabs it
        }
        return goals.isEmpty() ? NavGoal.exact(player.blockPosition()) : NavGoal.composite(goals);
    }

    /**
     * Baritone {@code MineProcess.coalesce} with {@code forceInternalMining=true}
     * (its DEFAULT) — pick the mining-stance goal for one ore so the body never
     * stands BELOW the bottom of a vein/trunk. The blind {@code GoalThreeBlocks}
     * (feet up to two below every ore) was our divergence: it made a tree's
     * bottom log's −2 cell a valid stance, and bare-handed (logs dear, dirt
     * cheap) A* dug under to it. Baritone instead asks "is the block above / below
     * this one ALSO something I'm mining?": the bottom of a vertical run (target
     * above, plain ground below) gets {@code GoalBlock} — feet EXACTLY at the ore,
     * mined where you stand, never dug under.
     */
    private NavGoal coalesce(BlockPos loc) {
        boolean assumeVerticalShaftMine =
                !(player.level().getBlockState(loc.above()).getBlock()
                        instanceof net.minecraft.world.level.block.FallingBlock);
        boolean upwardGoal = internalMiningGoal(loc.above());
        boolean downwardGoal = internalMiningGoal(loc.below());
        boolean doubleDownwardGoal = internalMiningGoal(loc.below(2));
        if (upwardGoal == downwardGoal) {                       // symmetric vertically
            return (doubleDownwardGoal && assumeVerticalShaftMine)
                    ? NavGoal.mineColumn(loc, 2)                // GoalThreeBlocks
                    : NavGoal.mineColumn(loc, 1);              // GoalTwoBlocks
        }
        if (upwardGoal) {                                       // bottom of a run: stand in it
            return NavGoal.mineColumn(loc, 0);                 // GoalBlock — feet exactly here
        }
        return (doubleDownwardGoal && assumeVerticalShaftMine) // top of a run, more below
                ? NavGoal.mineColumn(loc.below(), 1)           // GoalTwoBlocks(below)
                : NavGoal.mineColumn(loc.below(), 0);          // GoalBlock(below)
    }

    /**
     * Baritone {@code MineProcess.internalMiningGoal}: is {@code pos} also part of
     * what we're mining — a known target, a filter match, or (the air exception,
     * default on) already-broken air continuing the shaft? Used by {@link #coalesce}
     * to read the run a block sits in.
     */
    private boolean internalMiningGoal(BlockPos pos) {
        if (knownOres.contains(pos)) return true;
        net.minecraft.world.level.block.state.BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) return true;                         // internalMiningAirException
        return r.targets.contains(state.getBlock());
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
     * Baritone's MineProcess "shaft" — EXACT port: a known target in the body's
     * OWN feet column (x/z match), at or above feet, still solid, and reachable
     * (within reach + clear sight = {@code RotationUtils.reachable}). Mined in
     * place, no pathing. The A* (GoalThreeBlocks) is what gets the body INTO the
     * column; this only fires once it's there. No reach-from-the-side shortcut.
     */
    private BlockPos reachableTarget() {
        if (!player.onGround()) return null;
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        Vec3 eyes = player.getEyePosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos ore : knownOres) {
            if (ore.getX() != feet.getX() || ore.getZ() != feet.getZ()) continue;   // same column
            if (ore.getY() < feet.getY()) continue;                                  // at or above feet
            if (level.getBlockState(ore).isAir()) continue;
            if (!withinReach(ore) || !hasLineOfSight(eyes, ore)) continue;           // reachable
            double d = ore.distSqr(feet.above());
            if (d < bestD) {
                bestD = d;
                best = ore;
            }
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

    /** Advance the shared dig one tick (it switches to the best tool itself); on the tick it breaks,
     *  drop it from the ore list. The progress count is read from the inventory each tick, not here —
     *  one block can yield several items, and the drops take a moment to be picked up. */
    private void mineProgress(BlockPos pos) {
        if (digger.dig(pos)) {
            knownOres.remove(pos);
        }
    }

    // ---- item counting (Baritone MineProcess: count matching items in the inventory) ----

    /** Matching items currently in the inventory (sum of stack counts whose item the targets drop). */
    private int inventoryMatch() {
        if (dropItems.isEmpty()) return baseline;   // before start() resolved the set — no progress yet
        Inventory inv = player.getInventory();
        int sum = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && dropItems.contains(s.getItem())) sum += s.getCount();
        }
        return sum;
    }

    /** The item set the target blocks drop — Baritone {@code BlockOptionalMeta.drops}, via the server
     *  loot table rolled once per target with the best harvesting tool we carry (so an ore yields its
     *  ingot/gem, stone yields cobblestone, etc.). Falls back to the block's own item if it has no loot. */
    private Set<Item> computeDropItems() {
        Set<Item> items = new HashSet<>();
        if (!(player.level() instanceof ServerLevel level)) {
            for (Block b : r.targets) items.add(b.asItem());
            return items;
        }
        BlockPos origin = player.blockPosition();
        for (Block b : r.targets) {
            BlockState state = b.defaultBlockState();
            List<ItemStack> drops;
            try {
                drops = Block.getDrops(state, level, origin, null, player, bestToolFor(state));
            } catch (RuntimeException broken) {
                drops = List.of();
            }
            if (drops.isEmpty()) {
                items.add(b.asItem());
            } else {
                for (ItemStack d : drops) items.add(d.getItem());
            }
        }
        return items;
    }

    /** The inventory item that mines {@code state} fastest — the tool the dig will actually use, so the
     *  simulated drops match the real ones (e.g. respects a Silk Touch / Fortune pick if carried). */
    private ItemStack bestToolFor(BlockState state) {
        Inventory inv = player.getInventory();
        ItemStack best = inv.getItem(inv.selected);
        float bestSpeed = best.getDestroySpeed(state);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            float speed = s.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = s;
            }
        }
        return best;
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
                        || BlockMiningProgress.fluidBreakHazard(level, p) != null
                        || !BlockHelper.canHarvest(player.getInventory(), level.getBlockState(p)));
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
        // stopNav only clears the overlay when a nav exists; if we finished while
        // shaft-mining (nav == null) the goal boxes would otherwise linger, so clear
        // explicitly. Idempotent with stopNav's own clear.
        PathVizPublisher.clear(player);
        digger.cancel();
        if (scan != null) {
            scan.cancel(false);
            scan = null;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("gathered", r.getMined());
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(
                    "gathered " + r.getMined() + "/" + r.count + " " + r.label + " (" + doneReason + ")", data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out after gathering " + r.getMined() + "/" + r.count + " " + r.label, true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "interrupted after gathering " + r.getMined() + "/" + r.count + " " + r.label, false, true, data);
            case FAILED -> TaskResult.fail(doneReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
