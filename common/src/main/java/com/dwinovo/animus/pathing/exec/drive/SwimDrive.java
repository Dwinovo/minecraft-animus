package com.dwinovo.animus.pathing.exec.drive;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.exec.BodyMotor;
import com.dwinovo.animus.pathing.movement.Movement;
import net.minecraft.world.phys.Vec3;

/**
 * SWIM: stroke between water cells. Steered like a walk, with two aquatic
 * differences: arrival doesn't require {@code onGround} (a floating body is
 * never grounded), and the drive feeds gentle upward thrust whenever the
 * stroke needs height (rising stroke, or eyes underwater — the breath bar is
 * this drive's responsibility while a swim path is executing).
 */
public final class SwimDrive extends MovementDrive {

    /** Swim steering speed cap — water physics make high MoveControl speeds thrash. */
    private static final double SWIM_SPEED_CAP = 1.0;
    /** Upward acceleration per tick when height is needed. */
    private static final double RISE_THRUST = 0.04;
    private static final double MAX_RISE = 0.2;

    public SwimDrive(AnimusEntity entity, Movement mv, DriveHost host) {
        super(entity, mv, host);
    }

    @Override
    protected boolean placesFloor() {
        return false;   // strokes neither break nor place
    }

    @Override
    protected Result drive() {
        Vec3 c = Vec3.atBottomCenterOf(mv.dest);
        motor.steer(BodyMotor.Owner.PATH, c.x, c.y, c.z,
                Math.min(host.userSpeed(), SWIM_SPEED_CAP));
        entity.getLookControl().setLookAt(c.x, c.y + entity.getEyeHeight(), c.z);

        boolean needsHeight = mv.dest.getY() > entity.getBlockY() || entity.isUnderWater();
        if (needsHeight) {
            Vec3 d = entity.getDeltaMovement();
            motor.impulse(BodyMotor.Owner.PATH,
                    d.x, Math.min(d.y + RISE_THRUST, MAX_RISE), d.z);
        }

        // Cell occupancy, no onGround demand — we're floating.
        if (entity.blockPosition().equals(mv.dest)) {
            return Result.STEP_DONE;
        }
        return Result.RUNNING;
    }
}
