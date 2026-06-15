package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Finds a support face for placing a block at a target cell that the body's eyes
 * can ACTUALLY see — a port of Baritone {@code MovementHelper.attemptToPlaceABlock}.
 * For each candidate neighbour, raycast from the eye toward the shared-face centre;
 * accept only when the ray lands on that neighbour, on the face pointing back at the
 * target. This is how a real player's right-click resolves — never placing against
 * an occluded / out-of-sight face.
 *
 * <p>"Edge sneak" (the live-player feel): when {@code wouldSneak}, if the standing
 * eye can't see any face we retry from the CROUCHING eye position
 * ({@code inferSneakingEyePosition}) — exactly what a player does to lean over a
 * ledge and place against the block under their feet.
 *
 * <p>Shared by the {@code place_block} task and the pathfinder's bridge/pillar
 * scaffold placement, so both place identically and natively.
 */
public final class Placement {

    private Placement() {}

    /** Every neighbour we may place against — Baritone's all-dirs-except-UP. */
    private static final Direction[] FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    /**
     * A line-of-sight-verified support hit for placing at {@code placeAt}, or
     * {@code null} when no face is reachable from where the body stands.
     */
    public static BlockHitResult resolve(AnimusPlayer player, BlockPos placeAt, boolean wouldSneak) {
        double reach = player.blockInteractionRange();
        BlockHitResult hit = tryFromEye(player, placeAt, player.getEyePosition(), reach);
        if (hit == null && wouldSneak) {
            Vec3 sneakEye = new Vec3(player.getX(),
                    player.getY() + player.getEyeHeight(Pose.CROUCHING), player.getZ());
            hit = tryFromEye(player, placeAt, sneakEye, reach);
        }
        return hit;
    }

    private static BlockHitResult tryFromEye(AnimusPlayer player, BlockPos placeAt, Vec3 eye, double reach) {
        Level level = player.level();
        for (Direction dir : FACES) {
            BlockPos against = placeAt.relative(dir);
            if (!canPlaceAgainst(level, against)) continue;
            // Baritone attemptToPlaceABlock aim point: the shared-face centre but biased
            // 0.25 LOWER in Y — aiming low is what lets the ray clear the block's own top
            // and reach a side face when leaning over an edge.
            Vec3 facePoint = new Vec3(
                    (placeAt.getX() + against.getX() + 1.0) * 0.5,
                    (placeAt.getY() + against.getY() + 0.5) * 0.5,
                    (placeAt.getZ() + against.getZ() + 1.0) * 0.5);
            Vec3 toFace = facePoint.subtract(eye);
            if (toFace.lengthSqr() < 1.0e-6) continue;
            Vec3 end = eye.add(toFace.normalize().scale(reach));
            BlockHitResult res = level.clip(new ClipContext(
                    eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (res.getType() == HitResult.Type.BLOCK
                    && res.getBlockPos().equals(against)
                    && against.relative(res.getDirection()).equals(placeAt)) {
                return res;
            }
        }
        return null;
    }

    /** A geometric "is there any face to place against at all" pre-check (no
     *  line-of-sight test — used before the body has walked up to the target). */
    public static boolean hasAnySupport(Level level, BlockPos placeAt) {
        for (Direction dir : FACES) {
            if (canPlaceAgainst(level, placeAt.relative(dir))) return true;
        }
        return false;
    }

    /** Baritone canPlaceAgainst: a normal cube / glass presents a face (NOT every
     *  full-collision block — carpets, shulkers, scaffolding etc. are refused). */
    public static boolean canPlaceAgainst(Level level, BlockPos pos) {
        return BlockHelper.canPlaceAgainst(level, pos);
    }
}
