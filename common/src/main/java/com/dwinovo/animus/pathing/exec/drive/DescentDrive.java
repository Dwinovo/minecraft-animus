package com.dwinovo.animus.pathing.exec.drive;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.exec.BodyMotor;
import com.dwinovo.animus.pathing.movement.Movement;
import net.minecraft.world.phys.Vec3;

/**
 * The edge-leaving kinds — DESCEND, FALL, DIG_DOWN. Steered like
 * {@link SteerDrive} while grounded, but airborne it owns the physics that
 * keep the landing inside the planned column: horizontal braking (Baritone's
 * sneak brake) and a hard speed cap on the approach. Without these, momentum
 * built before the edge carries the landing across the approved cell into
 * the next drop — the "high-speed fall death".
 */
public final class DescentDrive extends SteerDrive {

    /** Per-tick horizontal velocity damping while airborne in the descent. */
    private static final double FALL_BRAKE_FACTOR = 0.6;

    public DescentDrive(AnimusEntity entity, Movement mv, DriveHost host) {
        super(entity, mv, host);
    }

    /**
     * Only FALL (a drop of 2+) needs momentum protection. A one-block DESCEND
     * is benign terrain vanilla mobs walk at full speed — capping/braking it
     * turned every downhill slope (a chain of DESCENDs) into a stuttering
     * crawl, the reported "suddenly slow going downhill". DIG_DOWN has no
     * edge to overshoot at all.
     */
    static boolean fallProtected(Movement.Kind k) {
        return k == Movement.Kind.FALL;
    }

    @Override
    protected Result drive() {
        if (mv.kind == Movement.Kind.FALL && !entity.onGround()) {
            // Airborne mid-descent: kill horizontal thrust and damp drift.
            // The motor keeps the facing (speed-0 aim) but adds no push.
            // Arrival is judged by the grounded path (super.drive()) on landing.
            motor.brakeHorizontal(BodyMotor.Owner.PATH, FALL_BRAKE_FACTOR);
            Vec3 dest = Vec3.atBottomCenterOf(mv.dest);
            motor.aim(BodyMotor.Owner.PATH, dest.x, dest.y, dest.z);
            entity.getLookControl().setLookAt(dest.x, dest.y + entity.getEyeHeight(), dest.z);
            return Result.RUNNING;
        }
        return super.drive();
    }

    /** Approaching a real fall edge: capped regardless of what comes next. */
    @Override
    protected double driveSpeed() {
        return mv.kind == Movement.Kind.FALL
                ? Math.min(host.userSpeed(), EDGE_SPEED_CAP)
                : super.driveSpeed();
    }
}
