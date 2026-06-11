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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Executes {@link UseItemTaskRecord}: right-click an item, optionally on a target
 * block, via the shared fake player ({@link FakePlayerUse}).
 *
 * <h2>State machine</h2>
 * <pre>
 *   RESOLVE → hold the item? in-air → USE; on-block → in reach? USE : GOTO.
 *   GOTO    → time-sliced A* to a standable spot beside the target → USE.
 *   USE     → fake-player useItem / useItemOn → SUCCESS if it took effect, else FAIL.
 * </pre>
 */
public final class UseItemTaskGoal extends LlmTaskGoal<UseItemTaskRecord> {

    private enum Phase { RESOLVE, GOTO, USE }

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

    public UseItemTaskGoal(AnimusEntity entity) {
        super(entity, UseItemTaskRecord.TOOL_NAME, UseItemTaskRecord.class);
    }

    @Override
    protected void onStart(UseItemTaskRecord r) {
        this.phase = Phase.RESOLVE;
        this.standSpot = null;
        this.replans = 0;
        this.resultData.clear();
    }

    @Override
    protected void onTick(UseItemTaskRecord r) {
        switch (phase) {
            case RESOLVE -> tickResolve(r);
            case GOTO -> tickGoto(r);
            case USE -> tickUse(r);
        }
    }

    private void tickResolve(UseItemTaskRecord r) {
        if (!(entity.level() instanceof net.minecraft.server.level.ServerLevel)) {
            fail("not on a server level");
            return;
        }
        // ensureInInventory pulls the stack back out of a hand slot if that's
        // where it lives (equipping moves items out of the backpack container).
        if (!entity.ensureInInventory(r.item)) {
            fail("no " + r.label + " in inventory or hands to use");
            return;
        }
        if (r.target == null) {
            phase = Phase.USE;            // in-air use, no walking
            return;
        }
        if (withinReach(r.target)) {
            phase = Phase.USE;
            return;
        }
        standSpot = findStandSpot(entity.level(), r.target);
        if (standSpot == null) {
            fail("no reachable spot to use " + r.label + " on "
                    + r.target.getX() + "," + r.target.getY() + "," + r.target.getZ());
            return;
        }
        if (startPlanning()) {
            phase = Phase.GOTO;
        } else {
            fail("can't path to a spot to use the item from");
        }
    }

    private void tickGoto(UseItemTaskRecord r) {
        if (withinReach(r.target)) {
            stopExecutor();
            phase = Phase.USE;
            return;
        }
        if (search != null) {
            if (search.step(NODES_PER_TICK) == AStarSearch.State.COMPUTING) {
                return;
            }
            Path path = search.result();
            search = null;
            if (path == null || path.isEmpty()) {
                fail("no path to the target block");
                return;
            }
            executor = new PathExecutor(entity, path, WALK_SPEED);
            return;
        }
        if (executor == null) {
            fail("no path to the target block");
            return;
        }
        switch (executor.tick()) {
            case RUNNING -> { /* keep walking */ }
            case ARRIVED, NEEDS_REPLAN -> {
                if (withinReach(r.target)) {
                    stopExecutor();
                    phase = Phase.USE;
                } else if (!startPlanning()) {
                    fail("can't reach the target block");
                }
            }
            case FAILED -> fail("can't reach the target block");
        }
    }

    private void tickUse(UseItemTaskRecord r) {
        entity.ensureInInventory(r.item);   // may have been re-equipped during the walk
        int slot = firstSlotOf(r.item);
        if (slot < 0) {
            fail("no " + r.label + " left to use");
            return;
        }
        InteractionResult result;
        String where;
        if (r.target == null) {
            result = FakePlayerUse.useInAir(entity, slot);
            where = "in the air";
        } else {
            BlockHitResult hit = hitToward(r.target);
            result = FakePlayerUse.useOnBlock(entity, slot, hit);
            where = "on " + r.target.getX() + "," + r.target.getY() + "," + r.target.getZ();
            resultData.put("x", r.target.getX());
            resultData.put("y", r.target.getY());
            resultData.put("z", r.target.getZ());
        }
        if (result.consumesAction()) {
            doneReason = "used " + r.label + " " + where;
            currentRecord.setState(TaskState.SUCCESS);
        } else {
            fail("using " + r.label + " " + where + " had no effect (wrong target or item?)");
        }
    }

    // ---- helpers ----

    private int firstSlotOf(net.minecraft.world.item.Item item) {
        SimpleContainer inv = entity.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.getItem() == item) return i;
        }
        return -1;
    }

    /**
     * A BlockHitResult aimed at {@code target}. For a solid target, clicks the
     * face nearest the entity (the normal case — e.g. an ender_eye on an
     * end_portal_frame). For an AIR target (e.g. lighting the interior of a
     * nether-portal frame), clicks an adjacent solid block with the face
     * pointing back at the cell: flint&steel places fire on
     * {@code clickedPos.relative(clickedFace)}, so this drops the fire INTO the
     * targeted air cell rather than onto the frame's outer face. Direction.values()
     * tries DOWN first, so the frame floor is preferred — fire lands inside.
     */
    private BlockHitResult hitToward(BlockPos target) {
        Level level = entity.level();
        if (level.getBlockState(target).isAir()) {
            for (Direction d : Direction.values()) {
                BlockPos neighbour = target.relative(d);
                if (!level.getBlockState(neighbour).isAir()) {
                    Direction face = d.getOpposite();   // neighbour.relative(face) == target
                    Vec3 hv = Vec3.atCenterOf(neighbour)
                            .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                    return new BlockHitResult(hv, face, neighbour, false);
                }
            }
            // No solid neighbour — fall through and click the air cell itself.
        }
        Vec3 ec = entity.getEyePosition();
        Vec3 bc = Vec3.atCenterOf(target);
        double dx = ec.x - bc.x, dy = ec.y - bc.y, dz = ec.z - bc.z;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        Direction face;
        if (ay >= ax && ay >= az) face = dy >= 0 ? Direction.UP : Direction.DOWN;
        else if (ax >= az) face = dx >= 0 ? Direction.EAST : Direction.WEST;
        else face = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        Vec3 hitVec = bc.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return new BlockHitResult(hitVec, face, target, false);
    }

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

    private void fail(String reason) {
        doneReason = reason;
        currentRecord.setState(TaskState.FAILED);
    }

    @Override
    protected TaskResult buildResult(UseItemTaskRecord r, TaskState finalState) {
        stopExecutor();
        entity.getNavigation().stop();
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, resultData);
            case TIMEOUT -> TaskResult.timeout("timed out before using " + r.label);
            case CANCELLED -> TaskResult.cancelled("use_item interrupted");
            case FAILED -> TaskResult.fail(doneReason);
            default -> TaskResult.fail("unexpected state: " + finalState);
        };
    }
}
