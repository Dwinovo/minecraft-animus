package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.exec.Navigator;
import com.dwinovo.animus.pathing.util.BlockScanner;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared executor skeleton for the chest-storage tasks: find a container
 * (explicit coordinates or nearest chest/barrel), walk to it with the
 * terrain-modifying pathfinder, then run the concrete {@link #operate} —
 * the same find/goto/operate shape as {@link LoadFurnaceTaskGoal}.
 */
abstract class AbstractContainerItemGoal<T extends ContainerItemTaskRecord>
        extends LlmTaskGoal<T> {

    private enum Phase { FIND, GOTO, OPERATE }

    private static final double WALK_SPEED = 1.0;

    private Phase phase = Phase.FIND;
    protected BlockPos containerPos;
    private Navigator nav;

    protected String doneReason = "done";
    protected final Map<String, Object> resultData = new HashMap<>();

    protected AbstractContainerItemGoal(AnimusEntity entity, String toolName, Class<T> recordClass) {
        super(entity, toolName, recordClass);
    }

    @Override
    protected void onStart(T r) {
        phase = Phase.FIND;
        containerPos = null;
        nav = null;
        doneReason = "done";
        resultData.clear();
    }

    @Override
    protected void onTick(T r) {
        switch (phase) {
            case FIND -> tickFind(r);
            case GOTO -> tickGoto(r);
            case OPERATE -> tickOperate(r);
        }
    }

    private void tickFind(T r) {
        Level level = entity.level();
        if (r.target != null) {
            if (!ContainerOps.isContainerBlock(level, r.target)) {
                fail("no chest/barrel at " + r.target.getX() + "," + r.target.getY() + ","
                        + r.target.getZ() + " — omit x/y/z to auto-pick the nearest one");
                return;
            }
            startToward(r.target);
            return;
        }
        List<BlockScanner.Hit> hits = BlockScanner.findWithin(
                level, entity.blockPosition(), r.searchRadius, ContainerOps.CONTAINER_BLOCKS);
        if (hits.isEmpty()) {
            fail("no chest or barrel within " + r.searchRadius + " blocks — place_block one "
                    + "first (craft a chest from 8 planks), or give x/y/z of a known container");
            return;
        }
        startToward(hits.get(0).pos());
    }

    private void startToward(BlockPos pos) {
        containerPos = pos;
        if (withinReach(pos)) {
            phase = Phase.OPERATE;
        } else {
            nav = new Navigator(entity, pos, WALK_SPEED, () -> withinReach(containerPos));
            phase = Phase.GOTO;
        }
    }

    private void tickGoto(T r) {
        switch (nav.tick()) {
            case RUNNING -> { /* keep walking */ }
            case ARRIVED -> { nav.stop(); phase = Phase.OPERATE; }
            case FAILED -> {
                if (withinReach(containerPos)) {
                    nav.stop();
                    phase = Phase.OPERATE;
                } else {
                    fail("can't reach the container at " + containerPos.getX() + ","
                            + containerPos.getY() + "," + containerPos.getZ()
                            + ": " + nav.failReason());
                }
            }
        }
    }

    private void tickOperate(T r) {
        Container container = ContainerOps.containerAt(entity.level(), containerPos);
        if (container == null) {
            fail("the container at " + containerPos.getX() + "," + containerPos.getY() + ","
                    + containerPos.getZ() + " is gone");
            return;
        }
        // Common result context: where, and what block it is (also feeds the
        // client's <known_blocks> memory via the work-block harvest).
        resultData.put("x", containerPos.getX());
        resultData.put("y", containerPos.getY());
        resultData.put("z", containerPos.getZ());
        resultData.put("block", BuiltInRegistries.BLOCK.getKey(
                entity.level().getBlockState(containerPos).getBlock()).toString());
        operate(r, container);
    }

    /** Do the actual slot work; set {@link #doneReason} + a terminal state. */
    protected abstract void operate(T record, Container container);

    protected void fail(String reason) {
        doneReason = reason;
        currentRecord.setState(TaskState.FAILED);
    }

    protected String posLabel() {
        return containerPos.getX() + "," + containerPos.getY() + "," + containerPos.getZ();
    }

    private boolean withinReach(BlockPos pos) {
        return entity.onGround()
                && entity.distanceToSqr(Vec3.atCenterOf(pos)) <= BlockMiningProgress.REACH_SQR;
    }

    @Override
    protected TaskResult buildResult(T r, TaskState finalState) {
        if (nav != null) nav.stop();
        entity.getNavigation().stop();
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, resultData);
            case TIMEOUT -> TaskResult.timeout("timed out: " + doneReason);
            case CANCELLED -> TaskResult.cancelled(r.getToolName() + " interrupted");
            case FAILED -> TaskResult.fail(doneReason, resultData);
            default -> TaskResult.fail("unexpected state: " + finalState);
        };
    }
}
