package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Predicate;

/**
 * Resolves a line-of-sight-verified hit for placing a block at {@code placeAt} — a 1:1 port of
 * Baritone {@code MovementHelper.attemptToPlaceABlock} + {@code RotationUtils.reachable}. Two stages,
 * in Baritone's precedence order:
 *
 * <ol>
 *   <li><b>Support face</b> — for each neighbour that {@link #canPlaceAgainst}, aim at the shared-face
 *       centre (biased low in Y so the ray clears the block's own top to reach a side face) and accept
 *       only when the ray lands on that neighbour, on the face pointing back at the target. This is the
 *       normal "place against the block below / beside it" path.</li>
 *   <li><b>Direct on the target</b> (fallback) — Baritone assumes a block sitting in the target cell is
 *       replaceable, so it just aims AT it and places (vanilla replaces it). It samples the target's
 *       own {@link VoxelShape}: the centre, then each of the six face centres, so an awkward thin shape
 *       (leaf_litter, grass, snow) is still hit from some angle. This is what lets a table go down on
 *       littered/grassy ground — the support-face ray is occluded by the litter, but aiming straight at
 *       the litter hits it and replaces it.</li>
 * </ol>
 *
 * <p>The eye is the crouching eye when {@code wouldSneak} (the body sneaks while placing, so that IS
 * the real eye) — Baritone's {@code inferSneakingEyePosition}, the "lean over a ledge" reach. Clip is
 * {@code OUTLINE}, exactly Baritone {@code RayTraceUtils.rayTraceTowards}.
 *
 * <p>Shared by the {@code place_block} task and the pathfinder's bridge/pillar scaffold placement, so
 * both place identically and natively.
 */
public final class Placement {

    private Placement() {}

    /** Every neighbour we may place against — Baritone's all-dirs-except-UP. */
    private static final Direction[] FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    /** Baritone {@code RotationUtils.BLOCK_SIDE_MULTIPLIERS}: down/up/N/S/W/E face-centre offsets,
     *  resolved against the target's actual shape bounds for the direct-reach sampling. */
    private static final Vec3[] BLOCK_SIDES = {
            new Vec3(0.5, 0, 0.5),   // Down
            new Vec3(0.5, 1, 0.5),   // Up
            new Vec3(0.5, 0.5, 0),   // North
            new Vec3(0.5, 0.5, 1),   // South
            new Vec3(0, 0.5, 0.5),   // West
            new Vec3(1, 0.5, 0.5)};  // East

    /**
     * A line-of-sight-verified support hit for placing at {@code placeAt}, or {@code null} when no
     * angle reaches a support face nor the (replaceable) target itself from where the body stands.
     */
    public static BlockHitResult resolve(AnimusPlayer player, BlockPos placeAt, boolean wouldSneak) {
        double reach = player.blockInteractionRange();
        BlockHitResult support = reachSupport(player, placeAt, reach, wouldSneak);   // preferred
        if (support != null) return support;
        return reachBlock(player, placeAt, reach, wouldSneak);                       // fallback: replace the target
    }

    /** Stage 1 — a reachable solid neighbour to place against (Baritone attemptToPlaceABlock loop). */
    private static BlockHitResult reachSupport(AnimusPlayer player, BlockPos placeAt, double reach, boolean wouldSneak) {
        Level level = player.level();
        for (Direction dir : FACES) {
            BlockPos against = placeAt.relative(dir);
            if (!canPlaceAgainst(level, against)) continue;
            // Aim point: the shared-face centre, biased 0.25 LOWER in Y — lets the ray clear the
            // block's own top and reach a side face when leaning over an edge (Baritone faceX/Y/Z).
            Vec3 facePoint = new Vec3(
                    (placeAt.getX() + against.getX() + 1.0) * 0.5,
                    (placeAt.getY() + against.getY() + 0.5) * 0.5,
                    (placeAt.getZ() + against.getZ() + 1.0) * 0.5);
            BlockHitResult hit = castFromEye(player, facePoint, reach, wouldSneak,
                    res -> res.getBlockPos().equals(against)
                            && against.relative(res.getDirection()).equals(placeAt));
            if (hit != null) return hit;
        }
        return null;
    }

    /** Stage 2 — aim directly at the (replaceable) target block, sampling its shape so a thin/odd
     *  shape is still hit from some angle (Baritone RotationUtils.reachable). */
    private static BlockHitResult reachBlock(AnimusPlayer player, BlockPos placeAt, double reach, boolean wouldSneak) {
        Level level = player.level();
        BlockState state = level.getBlockState(placeAt);
        if (state.isAir()) return null;   // nothing in the cell to hit / replace
        VoxelShape shape = state.getShape(level, placeAt);
        if (shape.isEmpty()) shape = Shapes.block();
        Predicate<BlockHitResult> onTarget = res -> res.getBlockPos().equals(placeAt);

        BlockHitResult hit = castFromEye(player, blockCenter(level, placeAt), reach, wouldSneak, onTarget);
        if (hit != null) return hit;
        for (Vec3 m : BLOCK_SIDES) {
            double x = shape.min(Direction.Axis.X) * m.x + shape.max(Direction.Axis.X) * (1 - m.x);
            double y = shape.min(Direction.Axis.Y) * m.y + shape.max(Direction.Axis.Y) * (1 - m.y);
            double z = shape.min(Direction.Axis.Z) * m.z + shape.max(Direction.Axis.Z) * (1 - m.z);
            Vec3 point = new Vec3(placeAt.getX() + x, placeAt.getY() + y, placeAt.getZ() + z);
            hit = castFromEye(player, point, reach, wouldSneak, onTarget);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Raytrace from the (crouch-when-sneaking) eye toward {@code point}; return the hit if {@code ok}. */
    private static BlockHitResult castFromEye(AnimusPlayer player, Vec3 point, double reach,
                                              boolean wouldSneak, Predicate<BlockHitResult> ok) {
        Vec3 eye = wouldSneak
                ? new Vec3(player.getX(), player.getY() + player.getEyeHeight(Pose.CROUCHING), player.getZ())
                : player.getEyePosition();
        Vec3 toPoint = point.subtract(eye);
        if (toPoint.lengthSqr() < 1.0e-6) return null;
        Vec3 end = eye.add(toPoint.normalize().scale(reach));
        BlockHitResult res = player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return (res.getType() == HitResult.Type.BLOCK && ok.test(res)) ? res : null;
    }

    /** Baritone VecUtils.calculateBlockCenter: centre of the collision shape (geometric centre if empty). */
    private static Vec3 blockCenter(Level level, BlockPos pos) {
        VoxelShape s = level.getBlockState(pos).getCollisionShape(level, pos);
        if (s.isEmpty()) {
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        return new Vec3(
                pos.getX() + (s.min(Direction.Axis.X) + s.max(Direction.Axis.X)) / 2,
                pos.getY() + (s.min(Direction.Axis.Y) + s.max(Direction.Axis.Y)) / 2,
                pos.getZ() + (s.min(Direction.Axis.Z) + s.max(Direction.Axis.Z)) / 2);
    }

    /** A geometric "is there any face to place against at all" pre-check (no
     *  line-of-sight test — used before the body has walked up to the target). */
    public static boolean hasAnySupport(Level level, BlockPos placeAt) {
        for (Direction dir : FACES) {
            if (canPlaceAgainst(level, placeAt.relative(dir))) return true;
        }
        return false;
    }

    /** Baritone canPlaceAgainst: a normal cube / glass presents a face (NOT every full-collision
     *  block — carpets, shulkers, scaffolding etc. are refused). */
    public static boolean canPlaceAgainst(Level level, BlockPos pos) {
        return BlockHelper.canPlaceAgainst(level, pos);
    }
}
