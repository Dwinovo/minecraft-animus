package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.entity.AnimusPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * The live "edge sneak" block placement, shared by {@code place_block} and the
 * pathfinder's bridge / step scaffolding — a port of how Baritone physically
 * places a block (it never teleport-pops one in): HOLD SNEAK (so it can't walk
 * off the ledge), edge toward the target so the support face comes into view,
 * look at that face, and place. We commit either when the raycast genuinely
 * reaches the face (honest line of sight) or, having physically settled at the
 * rim after a real shuffle, against that same face — the body has actually done
 * the maneuver, it isn't placing from dead-centre.
 *
 * <p>The block source ({@code slotFinder}) and the done-check ({@code placed})
 * are injected so the same maneuver serves a specific block ({@code place_block})
 * or any scaffold block (the pathfinder).
 */
public final class PlaceManeuver {

    public enum Status { RUNNING, DONE, FAILED }

    private static final Direction[] FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};
    /** After this many ticks without placing, back off a step to re-find the angle
     *  (Baritone MovementAscend MOVE_BACK), then edge in again — alternating windows. */
    private static final int BACK_OFF_TICKS = 12;
    private static final int LIMIT_TICKS = 60;

    private final AnimusPlayer player;
    private final BlockPos placeAt;
    private final IntSupplier slotFinder;   // hotbar/inventory slot of a placeable block, -1 if none
    private final BooleanSupplier placed;    // is placeAt now filled the way we want?

    private BlockPos against;
    private Direction faceDir;               // from `against` back to the target
    private int ticks;
    private String failReason = "couldn't place";

    public PlaceManeuver(AnimusPlayer player, BlockPos placeAt,
                         IntSupplier slotFinder, BooleanSupplier placed) {
        this.player = player;
        this.placeAt = placeAt.immutable();
        this.slotFinder = slotFinder;
        this.placed = placed;
    }

    public String failReason() {
        return failReason;
    }

    public Status tick() {
        if (placed.getAsBoolean()) return Status.DONE;
        if (slotFinder.getAsInt() < 0) {
            failReason = "out of blocks to place";
            return Status.FAILED;
        }
        if (against == null && !resolveSupport()) {
            failReason = "nothing adjacent to place against at " + placeAt.toShortString();
            return Status.FAILED;
        }

        // Baritone aim point on the support face (0.25 low) — for the look.
        Vec3 facePoint = new Vec3(
                (placeAt.getX() + against.getX() + 1.0) * 0.5,
                (placeAt.getY() + against.getY() + 0.5) * 0.5,
                (placeAt.getZ() + against.getZ() + 1.0) * 0.5);

        // Hold sneak (won't walk off the ledge) and edge toward the support face. If
        // we've ground forward without success, back off a step to re-find the angle
        // (Baritone MOVE_BACK), alternating in BACK_OFF_TICKS windows.
        player.setShiftKeyDown(true);
        InputDriver.lookAt(player, facePoint);
        boolean backing = ticks >= BACK_OFF_TICKS && (ticks / BACK_OFF_TICKS) % 2 == 1;
        player.zza = backing ? -1.0f : 1.0f;
        player.xxa = 0.0f;
        player.setSprinting(false);

        // Place ONLY once actually crouching (Baritone crouch-confirm — the sneak takes
        // a tick to register) AND the raycast genuinely reaches a support face. Never
        // fabricate a hit: if line of sight never lands, we just keep trying / time out.
        if (player.isCrouching()) {
            BlockHitResult hit = Placement.resolve(player, placeAt, true);
            if (hit != null && doPlace(hit)) {
                return Status.DONE;
            }
        }
        if (++ticks > LIMIT_TICKS) {
            failReason = "couldn't get a line of sight to place at " + placeAt.toShortString();
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    private boolean resolveSupport() {
        for (Direction dir : FACES) {
            BlockPos neighbour = placeAt.relative(dir);
            if (Placement.canPlaceAgainst(player.level(), neighbour)) {
                against = neighbour;
                faceDir = dir.getOpposite();
                return true;
            }
        }
        return false;
    }

    private boolean doPlace(BlockHitResult hit) {
        int slot = slotFinder.getAsInt();
        if (slot < 0) return false;
        ItemStack stack = player.getInventory().getItem(slot);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        Interaction.useBlock(player, hit, InteractionHand.MAIN_HAND).tick();
        return placed.getAsBoolean();
    }

    /** Release sneak / halt — call when the owning task or move ends. */
    public void stop() {
        player.setShiftKeyDown(false);
        InputDriver.halt(player);
    }
}
