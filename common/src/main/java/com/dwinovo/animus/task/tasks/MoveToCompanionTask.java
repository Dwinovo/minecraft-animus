package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.calc.NavGoal;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code move_to} on the companion player body — a 1:1 port of Baritone's
 * {@code goto}, whose goal type is chosen by which coordinates were supplied
 * ({@link MoveToTaskRecord.Kind}):
 * <ul>
 *   <li>{@link MoveToTaskRecord.Kind#COLUMN} → {@link NavGoal#column} ({@code GoalXZ}):
 *       reach the (x,z) location at any height — the default "go there", a wrong/
 *       absent Y can never make it unreachable;</li>
 *   <li>{@link MoveToTaskRecord.Kind#BLOCK} → {@link NavGoal#exact} ({@code GoalBlock}):
 *       one exact cell;</li>
 *   <li>{@link MoveToTaskRecord.Kind#YLEVEL} → {@link NavGoal#yLevel} ({@code GoalYLevel}):
 *       reach a target elevation.</li>
 * </ul>
 * The planner is untouched; only the goal/arrival/result semantics differ per kind.
 * Results always echo the ACTUAL position reached (and the real ground height) so
 * the model learns the terrain and which intent to use next time.
 */
public final class MoveToCompanionTask implements CompanionTask {

    private static final long TICKS_PER_BLOCK = 20;
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;
    /** When the planner CAN'T reach the exact goal, a stop within this of the
     *  requested column still counts as "got there" (a teaching success, not a
     *  thrash). This is the only tolerance — arrival itself is exact. */
    private static final double NEAR_SUCCESS_RADIUS = 3.0;
    /** Once the planner can't get closer (e.g. it stopped at the water surface above an
     *  underwater goal), keep the task alive this many ticks of NO progress before giving
     *  up — long enough for the body to passively drift onto a reachable underwater target,
     *  short enough to bail under an out-of-reach above-water one. */
    private static final int MAX_SETTLE_TICKS = 60;

    private final AnimusPlayer player;
    private final MoveToTaskRecord r;

    private final int bx;
    private final int by;
    private final int bz;
    private final BlockPos blockTarget;   // only meaningful for BLOCK kind

    private PlayerNav nav;
    private double bestDist = Double.MAX_VALUE;   // closest we've gotten to the goal
    private int settleTicks = 0;                  // ticks of no progress after the planner gave up

    public MoveToCompanionTask(AnimusPlayer player, MoveToTaskRecord record) {
        this.player = player;
        this.r = record;
        this.bx = r.x != null ? (int) Math.floor(r.x) : 0;
        this.by = r.y != null ? (int) Math.floor(r.y) : 0;
        this.bz = r.z != null ? (int) Math.floor(r.z) : 0;
        this.blockTarget = new BlockPos(bx, by, bz);
    }

    @Override
    public void start() {
        if (reached()) {
            r.setState(TaskState.SUCCESS);
            return;
        }
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (repDistance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        nav = PlayerNav.toGoal(player, this::goal, r.speed, this::reached);
        // Highlight the ACTUAL requested cell (not the path's best-effort end) so the overlay
        // box sits on the real target — e.g. a BLOCK goal under/over water that the path can
        // only approach to the surface. Mirrors how Baritone always renders the goal itself.
        if (r.kind == MoveToTaskRecord.Kind.BLOCK) {
            nav.setHighlights(() -> java.util.List.of(blockTarget));
        }
    }

    /** The Baritone goal for this move's kind. */
    private NavGoal goal() {
        return switch (r.kind) {
            case BLOCK -> NavGoal.exact(blockTarget);
            case COLUMN -> NavGoal.column(bx, bz);
            case YLEVEL -> NavGoal.yLevel(by);
        };
    }

    /** Live arrival — exact, 1:1 with the Baritone goal's {@code isInGoal}:
     *  GoalBlock (feet == cell), GoalXZ (feet x/z == target), GoalYLevel (feet y == level).
     *  YLEVEL additionally requires being ON THE GROUND: a pillar reaches the target y at
     *  the jump APEX a tick before its support block is placed, so without the onGround
     *  gate we'd declare success mid-air, pre-empt the place, and fall back (the stray
     *  extra hop). onGround makes the body actually settle on the placed block. */
    /** Slab-aware feet cell (Baritone playerFeet) — the pathing node, not raw blockPosition. */
    private BlockPos feet() {
        return com.dwinovo.animus.pathing.util.BlockHelper.playerFeet(
                player.level(), player.getX(), player.getY(), player.getZ());
    }

    private boolean reached() {
        BlockPos feet = feet();
        return switch (r.kind) {
            case BLOCK -> feet.equals(blockTarget);
            case COLUMN -> feet.getX() == bx && feet.getZ() == bz;
            case YLEVEL -> feet.getY() == by && player.onGround();
        };
    }

    @Override
    public TaskState tick() {
        if (nav == null) return TaskState.FAILED;
        if (reached()) return TaskState.SUCCESS;
        // Track passive progress toward the goal: the planner stops at the water surface
        // above an underwater target, but the body keeps drifting toward it on its own (it
        // sinks). Reset the settle timer whenever we get closer.
        double d = repDistance();
        if (d < bestDist - 0.1) {
            bestDist = d;
            settleTicks = 0;
        } else {
            settleTicks++;
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> TaskState.SUCCESS;
            case FAILED -> {
                // The planner can't get closer. In water, keep waiting while the body is
                // still drifting toward the goal (sinking onto an underwater target); give
                // up only once it's stopped making progress (bobbing at the surface below an
                // out-of-reach above-water target). Mirrors how Baritone settles onto an
                // underwater goal but stalls under an air one. On land a failure is final.
                if (player.isInWater() && settleTicks < MAX_SETTLE_TICKS) {
                    yield TaskState.RUNNING;
                }
                // Otherwise: as close as the terrain allows → (teaching) success or fail.
                yield closeEnoughToSucceed() ? TaskState.SUCCESS : TaskState.FAILED;
            }
        };
    }

    /** Did we get close enough to the destination to call it done (teaching success)? */
    private boolean closeEnoughToSucceed() {
        return switch (r.kind) {
            case BLOCK, COLUMN -> horizontalDistSqr(bx, bz) <= NEAR_SUCCESS_RADIUS * NEAR_SUCCESS_RADIUS;
            case YLEVEL -> Math.abs(feet().getY() - by) <= 1;
        };
    }

    private double horizontalDistSqr(int cellX, int cellZ) {
        double dx = (cellX + 0.5) - player.getX();
        double dz = (cellZ + 0.5) - player.getZ();
        return dx * dx + dz * dz;
    }

    /** Representative remaining distance (blocks) for the deadline estimate. */
    private double repDistance() {
        return switch (r.kind) {
            case BLOCK -> Math.sqrt(player.distanceToSqr(bx + 0.5, by, bz + 0.5));
            case COLUMN -> Math.sqrt(horizontalDistSqr(bx, bz));
            case YLEVEL -> Math.abs(player.getY() - by);
        };
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        String failReason = nav != null ? nav.failReason() : "no path";
        if (nav != null) nav.stop();

        int gy = player.blockPosition().getY();
        Map<String, Object> data = new HashMap<>();
        data.put("final_x", player.getX());
        data.put("final_y", player.getY());
        data.put("final_z", player.getZ());
        data.put("ground_y", gy);

        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(arrivedMessage(gy), data);
            case TIMEOUT -> new TaskResult(false, timeoutMessage(gy), true, false, data);
            case CANCELLED -> new TaskResult(false, "cancelled before reaching target", false, true, data);
            case FAILED -> blockedResult(gy, failReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }

    /** Success copy — always names the real position so the model learns the terrain. */
    private String arrivedMessage(int gy) {
        return switch (r.kind) {
            case BLOCK -> {
                if (feet().equals(blockTarget)) {
                    yield "reached the exact cell " + bx + "," + by + "," + bz + ".";
                }
                // Got to the column but not the exact y (the usual "guessed Y was in
                // the air" case) — teach the model to drop Y for a location.
                int dy = by - gy;
                yield "arrived at location x=" + bx + " z=" + bz + ", standing on the ground at y=" + gy
                        + ". The exact cell y=" + by + " wasn't reachable (" + Math.abs(dy) + " blocks "
                        + (dy > 0 ? "up — likely mid-air" : "down — likely blocked")
                        + "); for a location, omit y and I resolve the surface.";
            }
            case COLUMN -> "arrived at location x=" + bx + " z=" + bz
                    + ", standing on the ground at y=" + gy + ".";
            case YLEVEL -> "reached elevation y=" + gy
                    + (gy == by ? "." : " (requested y=" + by + ").");
        };
    }

    private String timeoutMessage(int gy) {
        double remaining = repDistance();
        return "timed out " + String.format("%.1f", remaining) + " blocks from target (now at "
                + bx(gy) + "); call move_to again with the same target to resume.";
    }

    private String bx(int gy) {
        return String.format("%.0f,%d,%.0f", player.getX(), gy, player.getZ());
    }

    /** A planner failure that wasn't close enough to count as arrival. */
    private TaskResult blockedResult(int gy, String failReason, Map<String, Object> data) {
        double remaining = repDistance();
        String where = switch (r.kind) {
            case BLOCK, COLUMN -> "location x=" + bx + " z=" + bz;
            case YLEVEL -> "elevation y=" + by;
        };
        String msg = "blocked: got within " + String.format("%.1f", remaining) + " blocks of " + where
                + " (now on the ground at y=" + gy + "). " + failReason
                + ". Try a nearer waypoint, scan_blocks for a way through, or equip a pickaxe to tunnel.";
        return TaskResult.fail(msg, data);
    }
}
