package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.calc.AStar;
import com.dwinovo.animus.pathing.calc.AStarSearch;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.Path;
import com.dwinovo.animus.pathing.exec.PathExecutor;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Placer for {@link PlaceBlockTaskRecord}: walk to a standable spot beside the
 * target and place one block from inventory there.
 *
 * <h2>State machine</h2>
 * <pre>
 *   RESOLVE → hold the block? target empty? has a solid neighbour to attach to?
 *             pick a standable spot beside it. In reach → PLACE; else GOTO.
 *   GOTO    → time-sliced A* to the stand spot (bridging/digging like move_to);
 *             in reach of the target → PLACE.
 *   PLACE   → setBlock + consume one from inventory → SUCCESS.
 * </pre>
 *
 * <p>Placement is server-authoritative ({@code level.setBlock} with the block's
 * default state), like {@link CraftTaskGoal}'s table placement — we don't
 * simulate a player right-click. We DO mirror Voyager's "no floating block"
 * rule via the solid-neighbour check, so the model gets taught real placement
 * sense.
 */
public final class PlaceBlockTaskGoal extends LlmTaskGoal<PlaceBlockTaskRecord> {

    private enum Phase { RESOLVE, GOTO, PLACE }

    private static final int NODES_PER_TICK = AStar.DEFAULT_NODES_PER_TICK;
    private static final double WALK_SPEED = 1.0;
    private static final int MAX_REPLANS = 12;

    private final AStar astar = new AStar();

    private Phase phase = Phase.RESOLVE;
    private BlockPos standSpot;
    private AStarSearch search;
    private PathExecutor executor;
    private int replans = 0;

    private String doneReason = "done";
    private final Map<String, Object> resultData = new HashMap<>();

    public PlaceBlockTaskGoal(AnimusEntity entity) {
        super(entity, PlaceBlockTaskRecord.TOOL_NAME, PlaceBlockTaskRecord.class);
    }

    @Override
    protected void onStart(PlaceBlockTaskRecord r) {
        this.phase = Phase.RESOLVE;
        this.standSpot = null;
        this.replans = 0;
        this.resultData.clear();
    }

    @Override
    protected void onTick(PlaceBlockTaskRecord r) {
        switch (phase) {
            case RESOLVE -> tickResolve(r);
            case GOTO -> tickGoto(r);
            case PLACE -> tickPlace(r);
        }
    }

    private void tickResolve(PlaceBlockTaskRecord r) {
        Level level = entity.level();
        if (entity.getInventory().countItem(r.item) <= 0) {
            fail("no " + r.label + " in inventory to place");
            return;
        }
        if (!BlockHelper.isReplaceableForPlacement(level, r.pos)) {
            fail("target " + coords(r.pos) + " is occupied by "
                    + net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(level.getBlockState(r.pos).getBlock()).getPath());
            return;
        }
        if (solidNeighbour(level, r.pos) == null) {
            fail("can't place a floating " + r.label + " at " + coords(r.pos)
                    + " — it needs a solid block next to it to attach to");
            return;
        }
        if (occupiesSelf(r.pos)) {
            fail("can't place a block on myself — that cell is where I'm standing");
            return;
        }
        if (withinReach(r.pos)) {
            phase = Phase.PLACE;
            return;
        }
        standSpot = findStandSpot(level, r.pos);
        if (standSpot == null) {
            fail("no reachable spot to stand and place at " + coords(r.pos));
            return;
        }
        if (startPlanning()) {
            phase = Phase.GOTO;
        } else {
            fail("can't path to a spot to place from");
        }
    }

    private void tickGoto(PlaceBlockTaskRecord r) {
        if (withinReach(r.pos)) {
            stopExecutor();
            phase = Phase.PLACE;
            return;
        }
        if (search != null) {
            if (search.step(NODES_PER_TICK) == AStarSearch.State.COMPUTING) {
                return;
            }
            Path path = search.result();
            search = null;
            if (path == null || path.isEmpty()) {
                fail("no path to the placement spot");
                return;
            }
            executor = new PathExecutor(entity, path, WALK_SPEED);
            return;
        }
        if (executor == null) {
            fail("no path to the placement spot");
            return;
        }
        switch (executor.tick()) {
            case RUNNING -> { /* keep walking */ }
            case ARRIVED, NEEDS_REPLAN -> {
                if (withinReach(r.pos)) {
                    stopExecutor();
                    phase = Phase.PLACE;
                } else if (!startPlanning()) {
                    fail("can't reach the placement spot");
                }
            }
            case FAILED -> fail("can't reach the placement spot");
        }
    }

    private void tickPlace(PlaceBlockTaskRecord r) {
        Level level = entity.level();
        // Re-validate — the world or our footing may have shifted while walking.
        if (!BlockHelper.isReplaceableForPlacement(level, r.pos)) {
            fail("target " + coords(r.pos) + " got occupied before I could place");
            return;
        }
        if (occupiesSelf(r.pos)) {
            fail("can't place at " + coords(r.pos) + " — I'm standing there");
            return;
        }
        BlockPos ref = solidNeighbour(level, r.pos);
        if (ref == null) {
            fail("can't place a floating " + r.label + " at " + coords(r.pos) + " — no solid block to attach to");
            return;
        }
        int slot = firstSlotOf(r.item);
        if (slot < 0) {
            fail("no " + r.label + " left to place");
            return;
        }

        // Place like a player: click the reference block's face that points at the
        // target, so vanilla's BlockItem.place puts our block at r.pos with a
        // proper, context-derived state (stairs/log/chest facing, etc.). The fake
        // player consumes one item; FakePlayerUse reconciles it back to inventory.
        Direction face = directionFromTo(ref, r.pos);
        Vec3 hitVec = Vec3.atCenterOf(ref).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        FakePlayerUse.useOnBlock(entity, slot, new BlockHitResult(hitVec, face, ref, false));

        if (!level.getBlockState(r.pos).is(r.block)) {
            fail("placement of " + r.label + " didn't land at " + coords(r.pos)
                    + " (blocked or invalid spot — try another position)");
            return;
        }

        resultData.put("x", r.pos.getX());
        resultData.put("y", r.pos.getY());
        resultData.put("z", r.pos.getZ());
        resultData.put("block", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(r.block).toString());
        doneReason = "placed " + r.label + " at " + coords(r.pos);
        currentRecord.setState(TaskState.SUCCESS);
    }

    // ---- helpers ----

    /** A real (non-air, non-replaceable) neighbour block the new block can attach to, or null. */
    private static BlockPos solidNeighbour(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos n = pos.relative(dir);
            BlockState s = level.getBlockState(n);
            if (!s.isAir() && !s.canBeReplaced()) return n;
        }
        return null;
    }

    /** The Direction d such that {@code from.relative(d) == to} (they must be neighbours). */
    private static Direction directionFromTo(BlockPos from, BlockPos to) {
        return Direction.getApproximateNearest(
                to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
    }

    private int firstSlotOf(Item item) {
        SimpleContainer inv = entity.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.getItem() == item) return i;
        }
        return -1;
    }

    /** The target cell is one the entity currently occupies (feet or head). */
    private boolean occupiesSelf(BlockPos pos) {
        BlockPos feet = entity.blockPosition();
        return pos.equals(feet) || pos.equals(feet.above());
    }

    /** Nearest standable cell beside the target (its horizontal neighbours, and
     *  those one below) so the entity can stand next to it and reach it. */
    private BlockPos findStandSpot(Level level, BlockPos target) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int drop = 0; drop <= 1; drop++) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos cand = target.relative(dir).below(drop);
                if (!BlockHelper.isStandable(level, cand)) continue;
                double d = entity.distanceToSqr(Vec3.atCenterOf(cand));
                if (d < bestDist) {
                    bestDist = d;
                    best = cand;
                }
            }
        }
        return best;
    }

    private boolean withinReach(BlockPos pos) {
        return entity.onGround()
                && entity.distanceToSqr(Vec3.atCenterOf(pos)) <= BlockMiningProgress.REACH_SQR;
    }

    private boolean startPlanning() {
        stopExecutor();
        if (replans++ >= MAX_REPLANS) return false;
        NavContext ctx = new NavContext(entity);
        search = astar.newSearch(ctx, entity.blockPosition(), standSpot);
        return true;
    }

    private void stopExecutor() {
        if (executor != null) {
            executor.stop();
            executor = null;
        }
        search = null;
    }

    private static String coords(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private void fail(String reason) {
        doneReason = reason;
        currentRecord.setState(TaskState.FAILED);
    }

    @Override
    protected TaskResult buildResult(PlaceBlockTaskRecord r, TaskState finalState) {
        stopExecutor();
        entity.getNavigation().stop();
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, resultData);
            case TIMEOUT -> TaskResult.timeout("timed out before placing " + r.label);
            case CANCELLED -> TaskResult.cancelled("place_block interrupted");
            case FAILED -> TaskResult.fail(doneReason);
            default -> TaskResult.fail("unexpected state: " + finalState);
        };
    }
}
