package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.calc.Path;
import com.dwinovo.animus.pathing.exec.drive.DescentDrive;
import com.dwinovo.animus.pathing.exec.drive.DriveHost;
import com.dwinovo.animus.pathing.exec.drive.MovementDrive;
import com.dwinovo.animus.pathing.exec.drive.ParkourDrive;
import com.dwinovo.animus.pathing.exec.drive.PillarDrive;
import com.dwinovo.animus.pathing.exec.drive.SteerDrive;
import com.dwinovo.animus.pathing.movement.Movement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a computed {@link Path}, one movement at a time. After the phase-2
 * inversion this class owns only the CROSS-movement concerns:
 *
 * <ul>
 *   <li><b>Re-localization</b> — every tick, match the entity's real feet
 *       against nearby movements' {@code validPositions()} and resync the
 *       index (back/forward scan) instead of throwing the path away on every
 *       push, fall or overshoot.</li>
 *   <li><b>Metering</b> — a per-movement stall budget scaled to its planned
 *       cost, and an off-path budget for genuine divergence.</li>
 *   <li><b>Advancement</b> — swapping in the next movement's
 *       {@link MovementDrive} when the current one reports done.</li>
 * </ul>
 *
 * Everything kind-specific — steering, descent braking, pillar jump/place
 * cycles, parkour ballistics, the break/place prepare pipeline — lives in the
 * {@link MovementDrive} subclasses ({@code exec/drive/}), each owning its own
 * sub-state for exactly as long as its movement is current. Adding a movement
 * type (swim, ladder) means adding a drive class, not editing this loop.
 *
 * <h2>Replan signalling</h2>
 * {@link #tick()} returns a {@link Status}: {@code ARRIVED} ends the task,
 * {@code NEEDS_REPLAN} recomputes a fresh path (off-path, ran out of
 * scaffolding, world changed), {@code FAILED} is unrecoverable.
 */
public final class PathExecutor implements DriveHost {

    /** Verbose pathing diagnostics to the {@code Animus} logger at INFO.
     *  Off by default — per-tick movement logs drown everything else; flip on
     *  (or via debugger) when chasing an executor bug. */
    public static boolean VERBOSE = false;

    public enum Status { RUNNING, ARRIVED, NEEDS_REPLAN, FAILED }

    /** Movements scanned each side of the current index when resyncing the feet. */
    private static final int RESYNC_SCAN = 8;
    /** Consecutive ticks tolerated off-path (but near it) before we replan. */
    private static final int AWAY_BUDGET = 60;
    /** >3 blocks off the current movement → definitively shoved off, replan now. */
    private static final double HARD_DIST_SQR = 9.0;
    private final AnimusEntity entity;
    private final Path path;
    private final double speed;

    private int index = 0;
    private MovementDrive drive;

    /** Ticks spent on the current movement — a stall backstop scaled to its cost. */
    private int ticksOnCurrent = 0;
    /** Consecutive ticks the feet matched no nearby movement (off-path). */
    private int ticksAway = 0;

    public PathExecutor(AnimusEntity entity, Path path, double speed) {
        this.entity = entity;
        this.path = path;
        this.speed = speed;
        plog("new path: " + path.movements.size() + " moves, partial=" + path.partial
                + ", from=" + entity.blockPosition() + " kinds=" + summariseKinds());
    }

    public Status tick() {
        // No water special-case: SWIM is a first-class movement, so a path
        // can legitimately execute while deep in water. A LAND path knocked
        // into water fails re-localization (water cells are in no land move's
        // valid set) and replans — and the fresh search swims itself out.
        if (path.isEmpty() || index >= path.movements.size()) {
            // Nothing (left) to walk. Partial paths hand over to a fresh search.
            return path.partial ? Status.NEEDS_REPLAN : Status.ARRIVED;
        }

        // 1) RE-LOCALIZE: resync the index to where the entity actually is, or
        //    signal a replan if it's genuinely off-path.
        Status reloc = relocalize();
        if (reloc != null) return reloc;
        if (index >= path.movements.size()) {
            return path.partial ? Status.NEEDS_REPLAN : Status.ARRIVED;
        }

        Movement mv = path.movements.get(index);

        // Per-second heartbeat: position / node / counters.
        if (VERBOSE && entity.level().getGameTime() % 20 == 0) {
            Vec3 p = entity.position();
            plog(String.format(
                    "t=%d idx=%d/%d kind=%s pos=(%.2f,%.2f,%.2f) node=(%d,%d,%d) onGround=%b on=%d away=%d",
                    entity.level().getGameTime(), index, path.movements.size(), mv.kind,
                    p.x, p.y, p.z, mv.dest.getX(), mv.dest.getY(), mv.dest.getZ(),
                    entity.onGround(), ticksOnCurrent, ticksAway));
        }

        // 2) STALL BACKSTOP: if we've spent far longer on this movement than its
        //    planned cost (in ticks), something the re-localization can't see is
        //    wrong (walking into a wall that should be clear) — replan.
        ticksOnCurrent++;
        int budget = (int) (mv.cost * 4) + 60;
        if (ticksOnCurrent > budget) {
            plog(String.format("REPLAN stall: %dt on idx=%d kind=%s (budget=%d)",
                    ticksOnCurrent, index, mv.kind, budget));
            return Status.NEEDS_REPLAN;
        }

        // 3) DRIVE — the movement executes itself.
        if (drive == null || drive.movement() != mv) {
            if (drive != null) drive.stop();
            drive = createDrive(mv);
        }
        return switch (drive.tick()) {
            case RUNNING -> Status.RUNNING;
            case STEP_DONE -> {
                advance();
                yield Status.RUNNING;
            }
            case NEEDS_REPLAN -> Status.NEEDS_REPLAN;
        };
    }

    /** The drive class that owns this movement kind's execution. */
    private MovementDrive createDrive(Movement mv) {
        return switch (mv.kind) {
            case DESCEND, FALL, DIG_DOWN -> new DescentDrive(entity, mv, this);
            case PILLAR -> new PillarDrive(entity, mv, this);
            case PARKOUR -> new ParkourDrive(entity, mv, this);
            case SWIM -> new com.dwinovo.animus.pathing.exec.drive.SwimDrive(entity, mv, this);
            default -> new SteerDrive(entity, mv, this);
        };
    }

    // ---- RE-LOCALIZATION ----

    /**
     * Match the entity's real feet against nearby movements' valid feet-sets.
     * Returns {@code null} to keep executing (possibly after resyncing the
     * index), or {@link Status#NEEDS_REPLAN} when the entity is genuinely
     * off-path.
     */
    private Status relocalize() {
        BlockPos feet = entity.blockPosition();
        Movement cur = path.movements.get(index);
        // A committed parkour jump is ballistic — mid-flight the feet are over
        // the gap (in neither src nor dest); don't resync or replan until it
        // lands. The drive owns the launch state; we just ask.
        if (drive instanceof ParkourDrive pd && pd.launched()) {
            return null;
        }
        if (cur.validPositions().contains(feet)) {
            ticksAway = 0;
            return null;
        }
        // Pushed/teleported backward, or fell onto an earlier node: scan back.
        for (int i = index - 1; i >= Math.max(0, index - RESYNC_SCAN); i--) {
            if (path.movements.get(i).validPositions().contains(feet)) {
                jumpToIndex(i, "pushed back");
                return null;
            }
        }
        // Overshot / pushed ahead / parkoured past: scan forward.
        int last = path.movements.size() - 1;
        for (int i = index + 1; i <= Math.min(last, index + RESYNC_SCAN); i++) {
            if (path.movements.get(i).validPositions().contains(feet)) {
                jumpToIndex(i, "overshoot");
                return null;
            }
        }
        // Off every nearby movement. Tolerate briefly (the drive below tries to
        // walk back onto the path); replan if far off, or off for too long.
        ticksAway++;
        double distSqr = distSqToMove(cur);
        if (distSqr > HARD_DIST_SQR) {
            plog(String.format("REPLAN off-path: distSq=%.2f from idx=%d (shoved off)", distSqr, index));
            return Status.NEEDS_REPLAN;
        }
        if (ticksAway > AWAY_BUDGET) {
            plog(String.format("REPLAN off-path: %dt away from idx=%d", ticksAway, index));
            return Status.NEEDS_REPLAN;
        }
        return null;
    }

    /** Horizontal+vertical dist² from the entity to the nearer of the move's src/dest cells. */
    private double distSqToMove(Movement mv) {
        double a = entity.distanceToSqr(Vec3.atBottomCenterOf(mv.src));
        double b = entity.distanceToSqr(Vec3.atBottomCenterOf(mv.dest));
        return Math.min(a, b);
    }

    /** Resync to movement {@code i}, discarding the current drive's sub-state. */
    private void jumpToIndex(int i, String why) {
        plog("relocalize (" + why + "): idx " + index + " -> " + i
                + " kind=" + path.movements.get(i).kind);
        index = i;
        discardDrive();
        ticksAway = 0;
        ticksOnCurrent = 0;
    }

    private void advance() {
        index++;
        discardDrive();
        ticksAway = 0;
        ticksOnCurrent = 0;
        if (index < path.movements.size()) {
            Movement next = path.movements.get(index);
            plog("advance -> idx " + index + "/" + path.movements.size()
                    + " kind=" + next.kind + " dest=" + next.dest);
        } else {
            plog("advance -> idx " + index + "/" + path.movements.size() + " (path end)");
        }
    }

    private void discardDrive() {
        if (drive != null) {
            drive.stop();
            drive = null;
        }
    }

    // ---- DriveHost (the services drives may consult) ----

    @Override
    public Movement nextMovement() {
        int next = index + 1;
        return next < path.movements.size() ? path.movements.get(next) : null;
    }

    @Override
    public double userSpeed() {
        return speed;
    }

    @Override
    public void log(String msg) {
        plog(msg);
    }

    // ---- accessors / teardown ----

    /** Compact summary of the path's movement kinds for the new-path log. */
    private String summariseKinds() {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(path.movements.size(), 16);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(path.movements.get(i).kind);
        }
        if (path.movements.size() > n) sb.append(",…");
        return sb.append(']').toString();
    }

    private void plog(String msg) {
        if (VERBOSE) {
            Constants.LOG.info("[animus-path#{}] {}", entity.getId(), msg);
        }
    }

    /** True if this path stopped short of the goal (executor signals replan at its end). */
    public boolean isPartial() {
        return path.partial;
    }

    /** Feet position this path ends at — the root for precomputing the next segment. */
    public BlockPos pathEnd() {
        return path.end;
    }

    /** Movements left to execute (for deciding when to precompute the continuation). */
    public int remainingMovements() {
        return Math.max(0, path.movements.size() - index);
    }

    /** Release the drive (mining overlay, motor) — call when the task ends. */
    public void stop() {
        discardDrive();
        entity.motor().release(BodyMotor.Owner.PATH);
        entity.getNavigation().stop();
    }
}
