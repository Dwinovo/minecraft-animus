package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.entity.AnimusPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
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
 *
 * <p>Optional {@link Hints} steer orientation: the support face is ordered by the
 * requested {@code axis}, the aim point is biased high/low for the {@code half},
 * and a placement is held back (keep shuffling) until a dry-run
 * {@code getStateForPlacement} predicts the requested {@code facing}/half — the
 * body keeps repositioning until it can place the block the right way round, just
 * like a player walking to the correct side. Hints are inert for the pathfinder.
 */
public final class PlaceManeuver {

    public enum Status { RUNNING, DONE, FAILED }

    /** Orientation the caller wants the placed block to end up with (any field null = don't care). */
    public record Hints(Direction facing, Direction.Axis axis, Boolean topHalf) {
        public static final Hints NONE = new Hints(null, null, null);
        public boolean isEmpty() {
            return facing == null && axis == null && topHalf == null;
        }
    }

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
    private final Hints hints;
    private final Block block;               // for the dry-run; null for the pathfinder (no hints)

    private BlockPos against;
    private Direction faceDir;               // from `against` back to the target
    private int ticks;
    private String failReason = "couldn't place";

    /** Pathfinder / orientation-agnostic placement. */
    public PlaceManeuver(AnimusPlayer player, BlockPos placeAt,
                         IntSupplier slotFinder, BooleanSupplier placed) {
        this(player, placeAt, slotFinder, placed, Hints.NONE, null);
    }

    /** Oriented placement: {@code block} + {@code hints} drive the support-face / aim choice. */
    public PlaceManeuver(AnimusPlayer player, BlockPos placeAt,
                         IntSupplier slotFinder, BooleanSupplier placed,
                         Hints hints, Block block) {
        this.player = player;
        this.placeAt = placeAt.immutable();
        this.slotFinder = slotFinder;
        this.placed = placed;
        this.hints = hints == null ? Hints.NONE : hints;
        this.block = block;
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

        // Baritone aim point on the support face (0.25 low) — for the look. For a slab/stair `half`
        // hint, bias the click height up (top) or down (bottom) so the placement lands on that half.
        double aimY = (hints.topHalf() != null)
                ? placeAt.getY() + (hints.topHalf() ? 0.72 : 0.28)
                : (placeAt.getY() + against.getY() + 0.5) * 0.5;
        Vec3 facePoint = new Vec3(
                (placeAt.getX() + against.getX() + 1.0) * 0.5,
                aimY,
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
        // With orientation hints we hold back until a dry-run predicts the right state, so the
        // body keeps repositioning to place it the right way — but never past a grace window.
        if (player.isCrouching()) {
            BlockHitResult hit = Placement.resolve(player, placeAt, true);
            if (hit != null) {
                boolean orientationOk = hints.isEmpty()
                        || ticks > (LIMIT_TICKS * 3) / 5         // grace: take what we can get
                        || matchesHints(predict(hit));
                if (orientationOk && doPlace(hit)) {
                    return Status.DONE;
                }
            }
        }
        if (++ticks > LIMIT_TICKS) {
            failReason = "couldn't get a line of sight to place at " + placeAt.toShortString();
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    private boolean resolveSupport() {
        for (Direction dir : orderedFaces()) {
            BlockPos neighbour = placeAt.relative(dir);
            if (Placement.canPlaceAgainst(player.level(), neighbour)) {
                against = neighbour;
                faceDir = dir.getOpposite();
                return true;
            }
        }
        return false;
    }

    /** Try support faces in an order that tends to yield the requested pillar axis first; the clicked
     *  face's axis becomes the log axis (top face → Y, E/W face → X, N/S face → Z). */
    private Direction[] orderedFaces() {
        if (hints.axis() == null) return FACES;
        return switch (hints.axis()) {
            case Y -> new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            case X -> new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH, Direction.DOWN};
            case Z -> new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};
        };
    }

    /** The blockstate this hit would place right now (vanilla's own rules), or null if unknown. */
    private BlockState predict(BlockHitResult hit) {
        if (block == null) return null;
        try {
            return block.getStateForPlacement(
                    new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(block.asItem()), hit));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Does the predicted state satisfy every hint that applies to it? (Unknown property = no veto.) */
    private boolean matchesHints(BlockState s) {
        if (s == null) return true;
        if (hints.facing() != null) {
            Direction f = facingOf(s);
            if (f != null && f != hints.facing()) return false;
        }
        if (hints.axis() != null) {
            Direction.Axis a = axisOf(s);
            if (a != null && a != hints.axis()) return false;
        }
        if (hints.topHalf() != null) {
            Boolean top = topHalfOf(s);
            if (top != null && top.booleanValue() != hints.topHalf().booleanValue()) return false;
        }
        return true;
    }

    private static Direction facingOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.FACING)) return s.getValue(BlockStateProperties.FACING);
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return s.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return null;
    }

    private static Direction.Axis axisOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.AXIS)) return s.getValue(BlockStateProperties.AXIS);
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) return s.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        return null;
    }

    private static Boolean topHalfOf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType t = s.getValue(BlockStateProperties.SLAB_TYPE);
            return t == SlabType.DOUBLE ? null : t == SlabType.TOP;
        }
        if (s.hasProperty(BlockStateProperties.HALF)) {
            return s.getValue(BlockStateProperties.HALF) == Half.TOP;
        }
        return null;
    }

    private boolean doPlace(BlockHitResult hit) {
        int slot = slotFinder.getAsInt();
        if (slot < 0) return false;
        player.holdInHand(slot);   // real hotbar-select / swap-to-hand, not an aliasing overwrite
        Interaction.useBlock(player, hit, InteractionHand.MAIN_HAND).tick();
        return placed.getAsBoolean();
    }

    /** Release sneak / halt — call when the owning task or move ends. */
    public void stop() {
        player.setShiftKeyDown(false);
        InputDriver.halt(player);
    }
}
