package com.dwinovo.animus.pathing.exec.drive;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.exec.BodyMotor;
import com.dwinovo.animus.pathing.movement.Movement;
import com.dwinovo.animus.pathing.util.BlockHelper;
import net.minecraft.world.phys.Vec3;

/**
 * Locomotion for the steered, ground-bound kinds — TRAVERSE, DIAGONAL,
 * ASCEND: hand MoveControl a wanted-position and arrive by cell occupancy.
 * {@link DescentDrive} extends this for the edge-leaving kinds.
 */
public class SteerDrive extends MovementDrive {

    /**
     * Speed ceiling when the NEXT movement leaves the ground over an edge —
     * the edge is one node ahead and momentum doesn't stop at cell borders.
     */
    protected static final double EDGE_SPEED_CAP = 0.7;

    public SteerDrive(AnimusEntity entity, Movement mv, DriveHost host) {
        super(entity, mv, host);
    }

    @Override
    protected Result drive() {
        Vec3 steer = steerTarget();
        motor.steer(BodyMotor.Owner.PATH, steer.x, steer.y, steer.z, driveSpeed());
        entity.getLookControl().setLookAt(steer.x, steer.y + entity.getEyeHeight(), steer.z);

        // Step up onto an ASCEND's target block: jump only when grounded (one
        // jump per landing, never re-fired mid-air), still below the target,
        // and with head-room to rise. The step block itself was already placed
        // by the prepare pipeline, so there's no mid-air landing correction.
        if (mv.kind == Movement.Kind.ASCEND
                && entity.onGround()
                && entity.getY() < mv.dest.getY() - 0.05
                && BlockHelper.canWalkThrough(entity.level(), entity.blockPosition().above(2))) {
            motor.jump(BodyMotor.Owner.PATH);
        }

        // Arrive by CELL occupancy, never centre proximity: demanding ~0.6 of
        // the centre made every momentum landing walk back to a node it had
        // already crossed. Standing anywhere in the dest cell is arrived;
        // onGround keeps us from advancing mid-jump/mid-fall.
        if (entity.blockPosition().equals(mv.dest) && entity.onGround()) {
            return Result.STEP_DONE;
        }
        return Result.RUNNING;
    }

    /** The user speed, capped when the next node steps off a real fall edge. */
    protected double driveSpeed() {
        Movement next = host.nextMovement();
        boolean edgy = next != null && DescentDrive.fallProtected(next.kind);
        return edgy ? Math.min(host.userSpeed(), EDGE_SPEED_CAP) : host.userSpeed();
    }

    /**
     * Where to aim MoveControl: normally the move's dest, but once the entity
     * has crossed it — downhill momentum carries the feet past the node
     * mid-air — aiming there would steer it BACKWARDS (the mid-air
     * about-face). Aim at the next plain-walking node instead and let
     * cell-arrival / forward-resync advance the index when we land.
     */
    private Vec3 steerTarget() {
        Vec3 dest = Vec3.atBottomCenterOf(mv.dest);
        Movement next = host.nextMovement();
        if (!walkKind(mv.kind) || next == null) return dest;
        // Only overshoot toward a node that needs no preparation on arrival.
        if (!walkKind(next.kind) || !next.toBreak.isEmpty() || next.toPlace != null) return dest;
        double alongX = mv.dest.getX() - mv.src.getX();
        double alongZ = mv.dest.getZ() - mv.src.getZ();
        double toX = dest.x - entity.getX();
        double toZ = dest.z - entity.getZ();
        if (alongX * toX + alongZ * toZ < 0.0) {
            return Vec3.atBottomCenterOf(next.dest);
        }
        return dest;
    }

    /** Moves MoveControl can steer straight through without stopping. */
    static boolean walkKind(Movement.Kind k) {
        return k == Movement.Kind.TRAVERSE || k == Movement.Kind.DESCEND
                || k == Movement.Kind.FALL || k == Movement.Kind.DIAGONAL;
    }
}
