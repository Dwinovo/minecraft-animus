package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.Path;
import com.dwinovo.animus.pathing.movement.Movement;
import com.dwinovo.animus.pathing.util.PathSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a computed {@link Path} on a companion {@link AnimusPlayer} body. The
 * player-body twin of {@code PathExecutor}: it keeps the same cross-movement
 * spine — re-localization (match real feet against nearby movements'
 * {@code validPositions} and resync the index instead of replanning on every
 * push/overshoot), a per-movement stall budget, and advancement — but the
 * per-movement EXECUTION is input-driving ({@link InputDriver}) + the player's
 * own survival break/place, not the Mob's velocity drives.
 *
 * <p>Each movement runs as: clear its {@code toBreak} obstructions
 * ({@code gameMode.destroyBlock} — survival break, drops fall and the player
 * picks them up natively), place its {@code toPlace} scaffold, then step toward
 * {@code dest} (face it, push forward; hop for ascend/pillar/parkour) until the
 * feet reach it. This is a first cut tuned for compilation + wiring; movement
 * feel gets refined against in-game runs.
 */
public final class PlayerPathExecutor {

    public enum Status { RUNNING, ARRIVED, NEEDS_REPLAN, FAILED }

    /** Off-path bands (Baritone MAX_DIST_FROM_PATH 2 / MAX_MAX 3), squared. */
    private static final double SOFT_DIST_SQR =
            PathSettings.MAX_DIST_FROM_PATH * PathSettings.MAX_DIST_FROM_PATH;
    private static final double HARD_DIST_SQR =
            PathSettings.MAX_MAX_DIST_FROM_PATH * PathSettings.MAX_MAX_DIST_FROM_PATH;
    /** Break at most one obstruction every few ticks so it reads as work, not teleport-mining. */
    private static final int BREAK_INTERVAL = 4;

    private final AnimusPlayer player;
    private final Path path;
    private final double speed;

    private int index = 0;
    private int ticksOnCurrent = 0;
    private int ticksAway = 0;
    private int breakCooldown = 0;
    private boolean placedThisMove = false;

    public PlayerPathExecutor(AnimusPlayer player, Path path, double speed) {
        this.player = player;
        this.path = path;
        this.speed = speed;
    }

    public Status tick() {
        if (path.isEmpty() || index >= path.movements.size()) {
            return path.partial ? Status.NEEDS_REPLAN : Status.ARRIVED;
        }

        Status reloc = relocalize();
        if (reloc != null) return reloc;
        if (index >= path.movements.size()) {
            return path.partial ? Status.NEEDS_REPLAN : Status.ARRIVED;
        }

        Movement mv = path.movements.get(index);

        // Land move stuck underwater = off-plan; replan (the fresh search swims out).
        if (player.isUnderWater() && mv.kind != Movement.Kind.SWIM) {
            return Status.NEEDS_REPLAN;
        }

        ticksOnCurrent++;
        // Baritone: cancel a movement that overshoots its estimate by movementTimeoutTicks.
        if (ticksOnCurrent > mv.cost + PathSettings.MOVEMENT_TIMEOUT_TICKS) {
            return Status.NEEDS_REPLAN;
        }

        // 1) Clear obstructions for this move (one at a time, paced).
        BlockPos obstruction = nextObstruction(mv);
        if (obstruction != null) {
            InputDriver.halt(player);
            InputDriver.lookAt(player, Vec3.atCenterOf(obstruction));
            if (breakCooldown-- <= 0) {
                breakCooldown = BREAK_INTERVAL;
                player.gameMode.destroyBlock(obstruction);
            }
            return Status.RUNNING;
        }

        // 2) Place the scaffold floor under dest, if this move needs one.
        if (mv.toPlace != null && !placedThisMove) {
            InputDriver.halt(player);
            PlaceOutcome po = tryPlaceScaffold(mv.toPlace);
            if (po == PlaceOutcome.PLACED || po == PlaceOutcome.ALREADY) {
                placedThisMove = true;
            } else if (po == PlaceOutcome.OUT_OF_BLOCKS) {
                return Status.NEEDS_REPLAN;   // ran dry → replan with hasScaffold=false
            }
            return Status.RUNNING;
        }

        // 3) Step toward dest; arrive when the feet reach it.
        BlockPos feet = player.blockPosition();
        if (feet.equals(mv.dest)) {
            advance();
            return Status.RUNNING;
        }
        boolean sprint = speed >= 1.0 && mv.kind == Movement.Kind.TRAVERSE;
        InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.dest), sprint);
        if (needsHop(mv)) {
            InputDriver.jump(player);
        }
        return Status.RUNNING;
    }

    /** True if this move clears the ground and needs an explicit hop. */
    private boolean needsHop(Movement mv) {
        return switch (mv.kind) {
            case ASCEND, PILLAR, PARKOUR -> true;
            default -> mv.dest.getY() > player.blockPosition().getY();
        };
    }

    /** The next still-solid block this move must break, or null when its path is clear. */
    private BlockPos nextObstruction(Movement mv) {
        for (BlockPos pos : mv.toBreak) {
            if (!player.level().getBlockState(pos).isAir()) {
                return pos;
            }
        }
        return null;
    }

    // ---- scaffold placement (player survival place) ----

    private enum PlaceOutcome { PLACED, ALREADY, NO_SUPPORT, OUT_OF_BLOCKS }

    private PlaceOutcome tryPlaceScaffold(BlockPos cell) {
        if (!player.level().getBlockState(cell).isAir()
                && !player.level().getBlockState(cell).canBeReplaced()) {
            return PlaceOutcome.ALREADY;   // something already fills it
        }
        int slot = scaffoldSlot();
        if (slot < 0) return PlaceOutcome.OUT_OF_BLOCKS;
        BlockHitResult hit = supportClick(cell);
        if (hit == null) return PlaceOutcome.NO_SUPPORT;

        var inv = player.getInventory();
        ItemStack scaffold = inv.getItem(slot);
        player.setItemInHand(InteractionHand.MAIN_HAND, scaffold);   // hold it; vanilla consumes from here
        InputDriver.lookAt(player, hit.getLocation());
        player.setShiftKeyDown(true);
        try {
            player.gameMode.useItemOn(player, player.level(),
                    player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
        } finally {
            player.setShiftKeyDown(false);
        }
        boolean placed = !player.level().getBlockState(cell).isAir();
        return placed ? PlaceOutcome.PLACED : PlaceOutcome.NO_SUPPORT;
    }

    private int scaffoldSlot() {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (NavContext.isScaffold(inv.getItem(i))) return i;
        }
        return -1;
    }

    /** A clickable solid neighbour face that places into {@code cell} (below first). */
    private BlockHitResult supportClick(BlockPos cell) {
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos neighbour = cell.relative(d);
            if (player.level().getBlockState(neighbour).getCollisionShape(
                    player.level(), neighbour).isEmpty()) {
                continue;
            }
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(neighbour)
                    .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            return new BlockHitResult(hit, face, neighbour, false);
        }
        return null;
    }

    // ---- re-localization (mirrors PathExecutor) ----

    private Status relocalize() {
        BlockPos feet = player.blockPosition();
        Movement cur = path.movements.get(index);
        if (cur.validPositions().contains(feet)) {
            ticksAway = 0;
            return null;
        }
        // Backward: lag / pushed back — scan ALL earlier movements (Baritone).
        for (int i = index - 1; i >= 0; i--) {
            if (path.movements.get(i).validPositions().contains(feet)) {
                jumpToIndex(i);
                return null;
            }
        }
        // Forward skip +3: a movement signals its own completion (e.g. sneak-place),
        // so +1/+2 are deliberately skipped (Baritone PathExecutor).
        int last = path.movements.size() - 1;
        for (int i = index + 3; i <= last; i++) {
            if (path.movements.get(i).validPositions().contains(feet)) {
                jumpToIndex(i - 1);
                return null;
            }
        }
        // Off-path watchdog: Baritone's two bands — >3 blocks cancels immediately,
        // >2 blocks counts toward MAX_TICKS_AWAY (~10 s) before giving up.
        double distSqr = Math.min(
                player.distanceToSqr(Vec3.atBottomCenterOf(cur.src)),
                player.distanceToSqr(Vec3.atBottomCenterOf(cur.dest)));
        if (distSqr > HARD_DIST_SQR) {
            return Status.NEEDS_REPLAN;
        }
        if (distSqr > SOFT_DIST_SQR) {
            if (++ticksAway > PathSettings.MAX_TICKS_AWAY) {
                return Status.NEEDS_REPLAN;
            }
        } else {
            ticksAway = 0;
        }
        return null;
    }

    private void jumpToIndex(int i) {
        index = i;
        resetMoveState();
    }

    private void advance() {
        index++;
        resetMoveState();
    }

    private void resetMoveState() {
        ticksAway = 0;
        ticksOnCurrent = 0;
        breakCooldown = 0;
        placedThisMove = false;
    }

    public boolean isPartial() {
        return path.partial;
    }

    public BlockPos pathEnd() {
        return path.end;
    }

    public int remainingMovements() {
        return Math.max(0, path.movements.size() - index);
    }

    public void stop() {
        InputDriver.halt(player);
    }
}
