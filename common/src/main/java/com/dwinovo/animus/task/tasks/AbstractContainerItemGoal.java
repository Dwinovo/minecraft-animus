package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.pathing.util.BlockScanner;
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
        implements CompanionTask {

    private enum Phase { FIND, GOTO, OPERATE }

    private static final double WALK_SPEED = 1.0;

    protected final AnimusPlayer player;
    protected final T r;

    private Phase phase = Phase.FIND;
    protected BlockPos containerPos;
    private PlayerNav nav;

    protected String doneReason = "done";
    protected final Map<String, Object> resultData = new HashMap<>();

    protected AbstractContainerItemGoal(AnimusPlayer player, T record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        phase = Phase.FIND;
        containerPos = null;
        nav = null;
        doneReason = "done";
        resultData.clear();
    }

    @Override
    public TaskState tick() {
        switch (phase) {
            case FIND -> tickFind(r);
            case GOTO -> tickGoto(r);
            case OPERATE -> tickOperate(r);
        }
        return r.getState();
    }

    private void tickFind(T r) {
        Level level = player.level();
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
                level, player.blockPosition(), r.searchRadius, ContainerOps.CONTAINER_BLOCKS);
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
            nav = new PlayerNav(player, pos, WALK_SPEED, () -> withinReach(containerPos));
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
        Container container = ContainerOps.containerAt(player.level(), containerPos);
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
                player.level().getBlockState(containerPos).getBlock()).toString());
        operate(r, container);
    }

    /** Do the actual slot work; set {@link #doneReason} + a terminal state. */
    protected abstract void operate(T record, Container container);

    protected void fail(String reason) {
        doneReason = reason;
        r.setState(TaskState.FAILED);
    }

    protected String posLabel() {
        return containerPos.getX() + "," + containerPos.getY() + "," + containerPos.getZ();
    }

    private boolean withinReach(BlockPos pos) {
        return player.onGround()
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= BlockMiningProgress.REACH_SQR;
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (nav != null) nav.stop();
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, resultData);
            case TIMEOUT -> TaskResult.timeout("timed out: " + doneReason);
            case CANCELLED -> TaskResult.cancelled(r.getToolName() + " interrupted");
            case FAILED -> TaskResult.fail(doneReason, resultData);
            default -> TaskResult.fail("unexpected state: " + finalState);
        };
    }
}
