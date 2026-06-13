package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.Path;
import com.dwinovo.animus.pathing.movement.Movement;
import com.dwinovo.animus.pathing.movement.Moves;
import com.dwinovo.animus.pathing.util.ActionCosts;
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
    /** Builds a fresh world snapshot for per-tick cost re-verification. */
    private final java.util.function.Supplier<NavContext> ctxSupplier;

    private int index = 0;
    private int ticksOnCurrent = 0;
    private int ticksAway = 0;
    private int breakCooldown = 0;
    private boolean placedThisMove = false;
    /** The index whose original cost estimate we cached (Baritone costEstimateIndex). */
    private int costCheckIndex = -1;

    public PlayerPathExecutor(AnimusPlayer player, Path path, double speed,
                              java.util.function.Supplier<NavContext> ctxSupplier) {
        this.player = player;
        this.path = path;
        this.speed = speed;
        this.ctxSupplier = ctxSupplier;
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

        // Baritone cost re-verification: the world may have changed under a planned
        // path (someone broke/placed a block). Re-cost the current + the next few
        // movements against a fresh snapshot; bail if any became impossible, or if
        // the current one got materially more expensive than planned.
        Status reverify = verifyCosts();
        if (reverify != null) return reverify;

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

        // 3) Drive toward dest by movement kind — per-Movement updateState,
        //    mirroring Baritone's input-forcing timing.
        BlockPos feet = player.blockPosition();
        if (feet.equals(mv.dest)) {
            advance();
            return Status.RUNNING;
        }
        drive(mv);
        return Status.RUNNING;
    }

    /** Horizontal speed² this tick — used to gate jump timing (Baritone waits for motion). */
    private double horizontalSpeedSqr() {
        var v = player.getDeltaMovement();
        return v.x * v.x + v.z * v.z;
    }

    /** Horizontal distance from the body to a cell's centre. */
    private double horizontalDistTo(BlockPos cell) {
        double dx = (cell.getX() + 0.5) - player.getX();
        double dz = (cell.getZ() + 0.5) - player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Per-movement execution. Each kind forces the inputs Baritone's
     * {@code updateState} would: walk/diagonal aim + forward (+sprint); ascend
     * waits for forward motion before jumping; parkour holds the jump until clear
     * of the takeoff block; pillar sneaks, jumps and places underfoot at the apex;
     * descend/fall let gravity do the work; dig-down lets phase 1 break the floor.
     */
    private void drive(Movement mv) {
        player.setShiftKeyDown(false);   // default; pillar re-enables per tick
        Vec3 dest = Vec3.atBottomCenterOf(mv.dest);
        switch (mv.kind) {
            case TRAVERSE -> InputDriver.stepToward(player, dest, speed >= 1.0);
            case DIAGONAL -> InputDriver.stepToward(player, dest, speed >= 1.0);
            case ASCEND -> {
                InputDriver.stepToward(player, dest, false);
                // Jump only once we're actually moving forward and on the ground —
                // prevents the machine-gun hop that fires before alignment.
                if (player.onGround() && horizontalSpeedSqr() > 0.006) {
                    InputDriver.jump(player);
                }
            }
            case PARKOUR -> {
                int gap = Math.max(Math.abs(mv.dest.getX() - mv.src.getX()),
                        Math.abs(mv.dest.getZ() - mv.src.getZ()));
                InputDriver.stepToward(player, dest, gap >= 4);   // a 4-gap needs sprint physics
                // Hold the jump until clear of the takeoff block (Baritone dist==3 rule),
                // so the arc starts from the edge, not the centre.
                if (player.onGround() && horizontalDistTo(mv.src) > 0.7) {
                    InputDriver.jump(player);
                }
            }
            case PILLAR -> drivePillar(mv);
            case DESCEND, FALL -> InputDriver.stepToward(player, dest, false); // gravity descends
            case DIG_DOWN -> InputDriver.halt(player);   // phase 1 breaks the floor; gravity drops us
            case SWIM -> {
                InputDriver.stepToward(player, dest, false);
                if (mv.dest.getY() > player.blockPosition().getY()) {
                    InputDriver.jump(player);   // swim up toward the surface
                }
            }
        }
    }

    /**
     * Block-tower pillar (Baritone MovementPillar): sneak so we never step off the
     * column, stay centred, jump from the ground, and at the apex place a block in
     * the cell we just left so we land one higher.
     */
    private void drivePillar(Movement mv) {
        player.setShiftKeyDown(true);
        // Recentre on the column rather than walking off it.
        if (horizontalDistTo(mv.src) > 0.17) {
            InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.src), false);
        } else {
            InputDriver.halt(player);
        }
        if (player.onGround()) {
            InputDriver.jump(player);
        } else if (player.getY() > mv.dest.getY() - 0.1) {
            // Near the top of the jump — place the scaffold block underfoot.
            tryPlaceScaffold(mv.src);
        }
    }

    /**
     * Baritone PathExecutor cost re-verification. The current movement is re-costed
     * every tick (catches a block change directly in front of us); the lookahead
     * window is re-verified only when we just advanced to a new movement (Baritone's
     * {@code costEstimateIndex} guard). Cancels — triggering a replan — if any
     * movement became impossible ({@code COST_INF}) or the current one's live cost
     * rose by more than {@code maxCostIncrease} over its planned estimate.
     */
    private Status verifyCosts() {
        NavContext fresh = ctxSupplier.get();
        Movement cur = path.movements.get(index);
        double liveCur = recost(fresh, cur);
        if (liveCur >= ActionCosts.COST_INF) {
            return Status.NEEDS_REPLAN;
        }
        if (liveCur - cur.cost > PathSettings.MAX_COST_INCREASE) {
            return Status.NEEDS_REPLAN;
        }
        if (costCheckIndex != index) {
            costCheckIndex = index;
            int last = path.movements.size() - 1;
            int end = Math.min(last, index + PathSettings.COST_VERIFICATION_LOOKAHEAD);
            for (int i = index + 1; i <= end; i++) {
                if (recost(fresh, path.movements.get(i)) >= ActionCosts.COST_INF) {
                    return Status.NEEDS_REPLAN;
                }
            }
        }
        return null;
    }

    /**
     * Recompute a movement's live cost from a fresh world snapshot: regenerate the
     * moves out of its source cell and match the same kind + destination.
     * {@code COST_INF} if it's no longer producible (the world changed so the move
     * is gone).
     */
    private double recost(NavContext fresh, Movement mv) {
        for (Movement gen : Moves.generate(fresh, mv.src)) {
            if (gen.kind == mv.kind && gen.dest.equals(mv.dest)) {
                return gen.cost;
            }
        }
        return ActionCosts.COST_INF;
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

    /** Estimated ticks left in this segment — Σ cost of the unplayed movements. */
    public double remainingCost() {
        double c = 0.0;
        for (int i = Math.max(index, 0); i < path.movements.size(); i++) {
            c += path.movements.get(i).cost;
        }
        return c;
    }

    public void stop() {
        InputDriver.halt(player);
    }
}
