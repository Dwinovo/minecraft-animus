package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.exec.Navigator;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for {@link MoveToTaskRecord}. Drives the shared {@link Navigator}
 * (the terrain-modifying pathfinder) toward the requested coordinates: the
 * entity walks, bridges gaps with cobblestone/dirt from its own inventory,
 * mines through obstructions, and steps up ledges — all costed by what it
 * actually carries, planned while it keeps moving.
 *
 * <h2>Outcomes</h2>
 * <ul>
 *   <li>{@code SUCCESS} — within {@link #REACHED_DISTANCE_SQR} of the target.</li>
 *   <li>{@code FAILED} — no path even after replans (often: no bridging blocks
 *       and a gap in the way — the message hints to supply cobblestone/dirt).</li>
 *   <li>{@code TIMEOUT} / {@code CANCELLED} — base-class deadline / eviction.</li>
 * </ul>
 */
public final class MoveToTaskGoal extends LlmTaskGoal<MoveToTaskRecord> {

    /** ~2 blocks Euclidean — LLM navigation is happy with approximate arrival. */
    private static final double REACHED_DISTANCE_SQR = 4.0;

    /** Per-block journey budget on top of the tool's flat 30s: terrain work
     *  (pillaring, bridging, digging) runs about a second a block. */
    private static final long TICKS_PER_BLOCK = 20;
    /** Hard cap so a genuinely unreachable target still dies in finite time. */
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;

    private Navigator nav;
    /** Pre-navigation failure reason (e.g. diving rejection); null = ask nav. */
    private String failReasonOverride;

    public MoveToTaskGoal(AnimusEntity entity) {
        super(entity, MoveToTaskRecord.TOOL_NAME, MoveToTaskRecord.class);
    }

    @Override
    protected void onStart(MoveToTaskRecord r) {
        failReasonOverride = null;
        if (closeEnough(r)) {
            r.setState(TaskState.SUCCESS);
            return;
        }
        // We swim on the SURFACE but never dive (scope decision — underwater
        // work means 25× mining penalties and buoyancy management; draining
        // is the correct play). A target below the waterline would otherwise
        // burn the whole replan budget before failing with a generic message.
        if (requiresDiving(r)) {
            failReasonOverride = "the target is DEEP underwater — I swim on the surface "
                    + "but don't dive. Aim move_to at the shore or the water surface; if "
                    + "you need the bottom, drain it first (place_block sand/gravel or "
                    + "scoop water with use_item bucket)";
            r.setState(TaskState.FAILED);
            return;
        }
        // Scale the deadline with the actual journey. The tool layer stamped a
        // flat 30s without knowing where the entity stands; a long or vertical
        // trip (33 scaffold blocks of pillaring, say) is steady progress, not a
        // stall, and shouldn't be cut down mid-climb.
        double dist = Math.sqrt(entity.distanceToSqr(r.x, r.y, r.z));
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (dist * TICKS_PER_BLOCK));
        r.extendDeadlineTo(entity.level().getGameTime() + extra);
        nav = new Navigator(entity, BlockPos.containing(r.x, r.y, r.z), r.speed, () -> closeEnough(r));
    }

    @Override
    protected void onTick(MoveToTaskRecord r) {
        if (nav == null) {
            r.setState(TaskState.FAILED);
            return;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* keep going */ }
            // ARRIVED is returned exactly when our reached-predicate (closeEnough)
            // is true; FAILED means no usable path — either way the task is over.
            case ARRIVED -> r.setState(TaskState.SUCCESS);
            case FAILED -> r.setState(closeEnough(r) ? TaskState.SUCCESS : TaskState.FAILED);
        }
    }

    private boolean closeEnough(MoveToTaskRecord r) {
        return entity.distanceToSqr(r.x, r.y, r.z) <= REACHED_DISTANCE_SQR;
    }

    /** Did the model aim at a fluid cell? Teach instead of silently "reaching". */
    private boolean targetIsLiquid(MoveToTaskRecord r) {
        return !entity.level().getBlockState(BlockPos.containing(r.x, r.y, r.z))
                .getFluidState().isEmpty();
    }

    /**
     * Would standing at the target put the body BELOW the water surface? The
     * effective feet cell (the target itself, or the cell above it when the
     * target is solid lake-floor) and the cell above it both being water
     * means a submerged body — diving, which we don't do. A surface cell
     * (water feet, air head) stays reachable by surface swimming.
     */
    private boolean requiresDiving(MoveToTaskRecord r) {
        var level = entity.level();
        BlockPos g = BlockPos.containing(r.x, r.y, r.z);
        BlockPos feet = level.getBlockState(g).getFluidState()
                .is(net.minecraft.tags.FluidTags.WATER) ? g : g.above();
        return level.getBlockState(feet).getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                && level.getBlockState(feet.above()).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    @Override
    protected TaskResult buildResult(MoveToTaskRecord r, TaskState finalState) {
        int replans = nav != null ? nav.replans() : 0;
        String failReason = failReasonOverride != null ? failReasonOverride
                : (nav != null ? nav.failReason() : "target unreachable");
        if (nav != null) nav.stop();
        entity.getNavigation().stop();

        Map<String, Object> data = new HashMap<>();
        data.put("final_x", entity.getX());
        data.put("final_y", entity.getY());
        data.put("final_z", entity.getZ());
        data.put("target_x", r.x);
        data.put("target_y", r.y);
        data.put("target_z", r.z);
        double remaining = Math.sqrt(entity.distanceToSqr(r.x, r.y, r.z));
        data.put("distance_remaining", remaining);
        data.put("replans", replans);

        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(targetIsLiquid(r)
                    ? "reached the target area — note the exact target cell is open "
                            + "LIQUID, so I stopped at the nearest reachable spot (I can "
                            + "swim, but precise positioning needs solid ground — aim "
                            + "move_to at shores/banks)"
                    : "reached target", data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out before reaching target", true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "cancelled before reaching target", false, true, data);
            case FAILED -> TaskResult.fail(failReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
