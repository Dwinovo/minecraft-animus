package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.exec.Navigator;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for {@link BreakBlockTaskRecord}: walk within reach of ONE exact
 * cell and dig it with the vanilla-accurate mining engine (swing, crack
 * overlay, drops routed into the inventory). Construction surgery — clear the
 * cell a frame block must occupy, prune the leaves blocking a placement, undo
 * a misplace. Reuses {@link MineBlockTaskGoal}'s building blocks without its
 * scan loop.
 */
public final class BreakBlockTaskGoal extends LlmTaskGoal<BreakBlockTaskRecord> {

    private enum Phase { PATH, MINE }

    private static final double WALK_SPEED = 1.0;

    private final BlockMiningProgress mining;

    private Phase phase = Phase.PATH;
    private Navigator nav;
    private String brokenBlock = "?";
    private String doneReason = "done";

    public BreakBlockTaskGoal(AnimusEntity entity) {
        super(entity, BreakBlockTaskRecord.TOOL_NAME, BreakBlockTaskRecord.class);
        this.mining = new BlockMiningProgress(entity);
    }

    @Override
    protected void onStart(BreakBlockTaskRecord r) {
        nav = null;
        BlockState state = entity.level().getBlockState(r.target);
        brokenBlock = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (state.isAir()) {
            fail("nothing to break at " + posLabel(r) + " — it's already air");
            return;
        }
        String unmineable = BlockMiningProgress.checkMineable(entity.level(), r.target);
        if (unmineable != null) {
            fail("can't break " + brokenBlock + " at " + posLabel(r) + ": " + unmineable);
            return;
        }
        if (BlockMiningProgress.submergedBeyondReach(entity.level(), r.target)) {
            fail(brokenBlock + " at " + posLabel(r) + " is fully UNDERWATER — I don't "
                    + "dive. Drain it first: place_block sand/gravel beside it, or scoop "
                    + "the water above it with use_item(bucket), then break it");
            return;
        }
        // Same harvest gate as auto_mine: breaking with the wrong tool yields
        // no drops and teaches the model the wrong tier lesson.
        String requirement = BlockMiningProgress.harvestRequirement(entity, state);
        if (requirement != null) {
            fail(requirement);
            return;
        }
        if (withinReach(r)) {
            beginMine(r);
        } else {
            nav = new Navigator(entity, r.target, WALK_SPEED, () -> withinReach(r));
            phase = Phase.PATH;
        }
    }

    /**
     * Arm the mining engine for this target — {@code tryStart} snapshots the
     * block state and computes the dig budget; ticking the engine unarmed
     * compares against a null snapshot and instantly reports "block changed"
     * (the bug that made every break_block fail on its first swing).
     */
    private void beginMine(BreakBlockTaskRecord r) {
        // Stance discipline: dig the block from beside it, not from on top of
        // it, whenever sidestepping is possible. break_block is an explicit
        // coordinate command though — with no adjacent footing (pillar top)
        // the model deliberately asked to dig down, so proceed.
        if (entity.blockPosition().below().equals(r.target)) {
            BlockPos side = adjacentStance();
            if (side != null) {
                stopNav();
                nav = new Navigator(entity, side, WALK_SPEED,
                        () -> withinReach(r) && !entity.blockPosition().below().equals(r.target));
                phase = Phase.PATH;
                return;
            }
        }
        if (mining.tryStart(r.target)) {
            phase = Phase.MINE;
            return;
        }
        // Raced away between our checks and now.
        if (entity.level().getBlockState(r.target).isAir()) {
            doneReason = "the block at " + posLabel(r) + " is already gone";
            currentRecord.setState(TaskState.SUCCESS);
        } else {
            fail("can't dig " + posLabel(r) + " — the block became unbreakable");
        }
    }

    @Override
    protected void onTick(BreakBlockTaskRecord r) {
        switch (phase) {
            case PATH -> tickPath(r);
            case MINE -> tickMine(r);
        }
    }

    private void tickPath(BreakBlockTaskRecord r) {
        if (nav == null) {
            beginMine(r);
            return;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* keep walking */ }
            case ARRIVED -> { stopNav(); beginMine(r); }
            case FAILED -> {
                if (withinReach(r)) {
                    stopNav();
                    beginMine(r);
                } else {
                    fail("can't reach " + posLabel(r) + ": " + nav.failReason());
                }
            }
        }
    }

    private void tickMine(BreakBlockTaskRecord r) {
        if (!withinReach(r)) {
            // Knocked away mid-dig — re-approach.
            mining.cleanup(r.target);
            nav = new Navigator(entity, r.target, WALK_SPEED, () -> withinReach(r));
            phase = Phase.PATH;
            return;
        }
        switch (mining.tick(r.target)) {
            case IN_PROGRESS -> { /* keep swinging */ }
            case COMPLETED, FAILED_BLOCK_GONE -> {
                mining.cleanup(r.target);
                doneReason = "broke " + brokenBlock + " at " + posLabel(r)
                        + " (drops collected if any)";
                currentRecord.setState(TaskState.SUCCESS);
            }
            case FAILED_BLOCK_CHANGED -> {
                mining.cleanup(r.target);
                fail("the block at " + posLabel(r) + " changed while digging — inspect_block and retry");
            }
            case FAILED_OUT_OF_REACH -> {
                mining.cleanup(r.target);
                nav = new Navigator(entity, r.target, WALK_SPEED, () -> withinReach(r));
                phase = Phase.PATH;
            }
        }
    }

    private boolean withinReach(BreakBlockTaskRecord r) {
        return entity.onGround()
                && entity.distanceToSqr(Vec3.atCenterOf(r.target)) <= BlockMiningProgress.REACH_SQR;
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

    private static String posLabel(BreakBlockTaskRecord r) {
        return r.target.getX() + "," + r.target.getY() + "," + r.target.getZ();
    }

    private void stopNav() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
    }

    private void fail(String reason) {
        doneReason = reason;
        currentRecord.setState(TaskState.FAILED);
    }

    @Override
    protected TaskResult buildResult(BreakBlockTaskRecord r, TaskState finalState) {
        stopNav();
        entity.getNavigation().stop();
        mining.cleanup(r.target);
        Map<String, Object> data = new HashMap<>();
        data.put("x", r.target.getX());
        data.put("y", r.target.getY());
        data.put("z", r.target.getZ());
        data.put("block", brokenBlock);
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case TIMEOUT -> TaskResult.timeout("timed out before breaking " + posLabel(r));
            case CANCELLED -> TaskResult.cancelled("break_block interrupted");
            case FAILED -> TaskResult.fail(doneReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState);
        };
    }
}
