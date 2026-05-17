package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for {@link PathfindAndMineTaskRecord}. Two-phase state machine:
 *
 * <ol>
 *   <li>{@link Phase#PATHING} — {@code navigation.moveTo(blockPos, speed)}
 *       until the entity is within {@link BlockMiningProgress#REACH_SQR}.
 *       Pre-check via {@link BlockMiningProgress#checkMineable} runs at
 *       {@link #onStart} so the entity never walks to bedrock.</li>
 *   <li>{@link Phase#MINING} — hand off to {@link BlockMiningProgress},
 *       same swing / sound / particle / crack feedback as
 *       {@link MineBlockTaskGoal}.</li>
 * </ol>
 *
 * <h2>Failure modes</h2>
 * <ul>
 *   <li>{@code FAILED} — block unmineable (precheck), path unreachable, got
 *       stuck mid-path, path ended short, block changed mid-dig.</li>
 *   <li>{@code TIMEOUT} — tool layer deadline hit before completion.</li>
 *   <li>{@code CANCELLED} — selector evicted us (typically self-death).</li>
 *   <li>{@code FAILED_BLOCK_GONE} during MINING → counted as SUCCESS (intent
 *       satisfied even if someone else broke it for us).</li>
 * </ul>
 */
public final class PathfindAndMineTaskGoal extends LlmTaskGoal<PathfindAndMineTaskRecord> {

    private enum Phase { PATHING, MINING }

    private final BlockMiningProgress mining;
    private Phase phase;

    public PathfindAndMineTaskGoal(AnimusEntity entity) {
        super(entity, PathfindAndMineTaskRecord.TOOL_NAME, PathfindAndMineTaskRecord.class);
        this.mining = new BlockMiningProgress(entity);
    }

    @Override
    protected void onStart(PathfindAndMineTaskRecord r) {
        Level level = entity.level();
        String reason = BlockMiningProgress.checkMineable(level, r.pos);
        if (reason != null) {
            // Bedrock / air — no point walking over.
            r.setState(TaskState.FAILED);
            return;
        }

        Vec3 center = Vec3.atCenterOf(r.pos);
        if (entity.distanceToSqr(center) <= BlockMiningProgress.REACH_SQR) {
            // Already in range — skip pathing entirely.
            if (!mining.tryStart(r.pos)) {
                r.setState(TaskState.FAILED);
                return;
            }
            this.phase = Phase.MINING;
            return;
        }

        PathNavigation nav = entity.getNavigation();
        boolean ok = nav.moveTo(r.pos.getX() + 0.5, r.pos.getY(), r.pos.getZ() + 0.5, r.speed);
        if (!ok) {
            r.setState(TaskState.FAILED);
            return;
        }
        this.phase = Phase.PATHING;
    }

    @Override
    protected void onTick(PathfindAndMineTaskRecord r) {
        if (phase == Phase.PATHING) {
            Vec3 center = Vec3.atCenterOf(r.pos);
            if (entity.distanceToSqr(center) <= BlockMiningProgress.REACH_SQR) {
                entity.getNavigation().stop();
                if (!mining.tryStart(r.pos)) {
                    // Block changed between path-arrival and start.
                    r.setState(TaskState.FAILED);
                    return;
                }
                this.phase = Phase.MINING;
                return;
            }
            PathNavigation nav = entity.getNavigation();
            if (nav.isStuck()) {
                r.setState(TaskState.FAILED);
                return;
            }
            if (nav.isDone()) {
                // Path concluded but we're not in reach — partial path.
                r.setState(TaskState.FAILED);
            }
            return;
        }

        // Phase.MINING
        switch (mining.tick(r.pos)) {
            case IN_PROGRESS -> { /* keep going */ }
            case COMPLETED -> r.setState(TaskState.SUCCESS);
            case FAILED_BLOCK_GONE -> r.setState(TaskState.SUCCESS);
            case FAILED_OUT_OF_REACH, FAILED_BLOCK_CHANGED -> r.setState(TaskState.FAILED);
        }
    }

    @Override
    protected TaskResult buildResult(PathfindAndMineTaskRecord r, TaskState finalState) {
        // Always release navigation + crack overlay regardless of outcome.
        entity.getNavigation().stop();
        mining.cleanup(r.pos);

        Map<String, Object> data = new HashMap<>();
        data.put("x", r.pos.getX());
        data.put("y", r.pos.getY());
        data.put("z", r.pos.getZ());
        data.put("phase_reached", phase == null ? "none" : phase.name().toLowerCase());
        if (mining.startState() != null) {
            data.put("block", BuiltInRegistries.BLOCK.getKey(mining.startState().getBlock()).toString());
            data.put("ticks_spent", mining.progressTicks());
            data.put("ticks_needed", mining.totalTicksNeeded());
        }
        double remaining = Math.sqrt(entity.distanceToSqr(Vec3.atCenterOf(r.pos)));
        data.put("distance_remaining", remaining);

        return switch (finalState) {
            case SUCCESS -> TaskResult.ok("block mined after pathing", data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out (path too long or mining too slow without proper tool)",
                    true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "interrupted (self died or evicted)", false, true, data);
            case FAILED -> TaskResult.fail(failureMessage(r), data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }

    private String failureMessage(PathfindAndMineTaskRecord r) {
        Level level = entity.level();
        String reason = BlockMiningProgress.checkMineable(level, r.pos);
        if (reason != null) return reason;
        if (phase == Phase.PATHING) {
            PathNavigation nav = entity.getNavigation();
            if (nav.isStuck()) return "stuck mid-path";
            return "target unreachable (no valid path)";
        }
        Vec3 center = Vec3.atCenterOf(r.pos);
        if (entity.distanceToSqr(center) > BlockMiningProgress.REACH_SQR) {
            return "fell out of reach mid-dig";
        }
        return "block changed mid-dig";
    }
}
