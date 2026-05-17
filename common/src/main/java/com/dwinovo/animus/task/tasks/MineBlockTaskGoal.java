package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for {@link MineBlockTaskRecord}. Pure "atomic" miner — no
 * path-finding. If the block is out of {@link BlockMiningProgress#REACH_SQR}
 * reach (≈4.5 blocks, matches vanilla {@code block_interaction_range}) the
 * task fails immediately so the LLM can compose with {@code move_to} or
 * step up to {@code pathfind_and_mine}.
 *
 * <h2>Why no integrated pathing</h2>
 * Mineflayer / Project Malmo / MineDojo all split "dig" from "pathfind to
 * digable position" — the failure mode "out of reach" is far cleaner for an
 * LLM to recover from than a half-completed combined operation. The
 * combined op is implemented separately as {@link PathfindAndMineTaskGoal}.
 *
 * <p>All mining mechanics (tick-budget formula, swing/sound/particles, crack
 * overlay) live in the shared {@link BlockMiningProgress} helper so both
 * mining goals stay byte-for-byte identical in behaviour.
 */
public final class MineBlockTaskGoal extends LlmTaskGoal<MineBlockTaskRecord> {

    private final BlockMiningProgress mining;

    public MineBlockTaskGoal(AnimusEntity entity) {
        super(entity, MineBlockTaskRecord.TOOL_NAME, MineBlockTaskRecord.class);
        this.mining = new BlockMiningProgress(entity);
    }

    @Override
    protected void onStart(MineBlockTaskRecord r) {
        Vec3 center = Vec3.atCenterOf(r.pos);
        if (entity.distanceToSqr(center) > BlockMiningProgress.REACH_SQR) {
            r.setState(TaskState.FAILED);
            return;
        }
        Level level = entity.level();
        String reason = BlockMiningProgress.checkMineable(level, r.pos);
        if (reason != null) {
            r.setState(TaskState.FAILED);
            return;
        }
        if (!mining.tryStart(r.pos)) {
            r.setState(TaskState.FAILED);
        }
    }

    @Override
    protected void onTick(MineBlockTaskRecord r) {
        switch (mining.tick(r.pos)) {
            case IN_PROGRESS -> { /* keep going */ }
            case COMPLETED -> r.setState(TaskState.SUCCESS);
            // Someone else broke it for us — intent satisfied.
            case FAILED_BLOCK_GONE -> r.setState(TaskState.SUCCESS);
            case FAILED_OUT_OF_REACH, FAILED_BLOCK_CHANGED -> r.setState(TaskState.FAILED);
        }
    }

    @Override
    protected TaskResult buildResult(MineBlockTaskRecord r, TaskState finalState) {
        mining.cleanup(r.pos);

        Map<String, Object> data = new HashMap<>();
        data.put("x", r.pos.getX());
        data.put("y", r.pos.getY());
        data.put("z", r.pos.getZ());
        if (mining.startState() != null) {
            data.put("block", BuiltInRegistries.BLOCK.getKey(mining.startState().getBlock()).toString());
        }
        data.put("ticks_spent", mining.progressTicks());
        data.put("ticks_needed", mining.totalTicksNeeded());

        return switch (finalState) {
            case SUCCESS -> TaskResult.ok("block mined", data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out before block broke (no suitable tool?)", true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "mining interrupted", false, true, data);
            case FAILED -> TaskResult.fail(failureMessage(r), data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }

    private String failureMessage(MineBlockTaskRecord r) {
        Vec3 center = Vec3.atCenterOf(r.pos);
        if (entity.distanceToSqr(center) > BlockMiningProgress.REACH_SQR) {
            return "out of reach (> 4.5 blocks)";
        }
        String reason = BlockMiningProgress.checkMineable(entity.level(), r.pos);
        if (reason != null) return reason;
        return "block changed mid-dig";
    }
}
