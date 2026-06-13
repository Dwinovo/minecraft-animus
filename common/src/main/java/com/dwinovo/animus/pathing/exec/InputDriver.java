package com.dwinovo.animus.pathing.exec;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Drives a companion {@link ServerPlayer} body the way Carpet's
 * {@code EntityPlayerActionPack} drives a fake player: by setting the player's
 * movement INPUTS ({@code zza}/{@code xxa}, sprint, sneak, jump) and aim, then
 * letting vanilla player physics ({@code LivingEntity.travel}) do the actual
 * stepping, collision and 0.6-block step-up. Replaces the old {@code BodyMotor}
 * which wrote velocity directly onto a Mob's MoveControl.
 *
 * <p>A fake player has no client to send movement packets, so the server's own
 * player tick runs {@code travel} against these inputs and nothing overrides the
 * resulting position — that is what makes input-driving a server-side body work.
 * Inputs are momentary: set them every tick while moving, and {@link #halt} every
 * tick while stopped (otherwise the last forward input keeps it walking).
 */
public final class InputDriver {

    private InputDriver() {}

    /** Face {@code target} (yaw only) and push full forward. Call each tick while travelling. */
    public static void stepToward(ServerPlayer p, Vec3 target, boolean sprint) {
        faceYaw(p, target);
        p.zza = 1.0f;
        p.xxa = 0.0f;
        p.setSprinting(sprint && !p.isShiftKeyDown());
    }

    /** Aim the eyes at a point (yaw + pitch) — e.g. a block being mined or placed. */
    public static void lookAt(ServerPlayer p, Vec3 point) {
        p.lookAt(EntityAnchorArgument.Anchor.EYES, point);
    }

    /** One-shot hop (step-up assist / clear a gap), only meaningful on the ground. */
    public static void jump(ServerPlayer p) {
        if (p.onGround()) {
            p.jumpFromGround();
        }
    }

    public static void sneak(ServerPlayer p, boolean on) {
        p.setShiftKeyDown(on);
    }

    /** Zero all locomotion input. Call each tick while idle/arrived. */
    public static void halt(ServerPlayer p) {
        p.zza = 0.0f;
        p.xxa = 0.0f;
        p.setSprinting(false);
    }

    /** Turn the body (and head) to face {@code target} horizontally — travel goes where yaw points. */
    private static void faceYaw(ServerPlayer p, Vec3 target) {
        double dx = target.x - p.getX();
        double dz = target.z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
    }
}
