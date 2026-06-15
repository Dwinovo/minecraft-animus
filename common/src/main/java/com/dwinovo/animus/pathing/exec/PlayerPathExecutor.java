package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.Path;
import com.dwinovo.animus.pathing.movement.Movement;
import com.dwinovo.animus.pathing.movement.Moves;
import com.dwinovo.animus.pathing.util.ActionCosts;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.pathing.util.PathSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
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
 * <p>Each movement runs as: clear its {@code toBreak} obstructions (the native
 * {@link BlockDigger} — {@code handleBlockBreakAction} START/STOP, survival
 * timed, drops fall and the player picks them up natively), place its
 * {@code toPlace} scaffold, then step toward
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

    private final AnimusPlayer player;
    private final Path path;
    private final double speed;
    /** Builds a fresh world snapshot for per-tick cost re-verification. */
    private final java.util.function.Supplier<NavContext> ctxSupplier;

    private int index = 0;
    private int ticksOnCurrent = 0;
    private int ticksAway = 0;
    private boolean placedThisMove = false;
    /** The live edge-sneak scaffold placement for the current move (null when idle). */
    private PlaceManeuver placeManeuver;
    /** Progressive break of path obstructions (shared model with auto-mine). */
    private final BlockDigger digger;
    /** The index whose original cost estimate we cached (Baritone costEstimateIndex). */
    private int costCheckIndex = -1;

    public PlayerPathExecutor(AnimusPlayer player, Path path, double speed,
                              java.util.function.Supplier<NavContext> ctxSupplier) {
        this.player = player;
        this.path = path;
        this.speed = speed;
        this.ctxSupplier = ctxSupplier;
        this.digger = new BlockDigger(player);
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

        // Submerged = off-plan, EXCEPT when the current move is a swim-up: the surface
        // plane is where traverse/ascend live, so being underwater on one of those means
        // we were knocked under → replan from a valid surface cell. But a water-column
        // swim-up (PILLAR out of a water cell) is *meant* to run submerged — exempt it,
        // or the replan would pre-empt the only move that climbs back to the surface.
        boolean swimmingUp = mv.kind == Movement.Kind.PILLAR
                && BlockHelper.isWater(player.level(), mv.src);
        if (player.isUnderWater() && !swimmingUp) {
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

        // 1) Clear obstructions for this move (one at a time), breaking each
        //    progressively over its real hardness time — Baritone holds the dig,
        //    it doesn't pop the block instantly.
        BlockPos obstruction = nextObstruction(mv);
        if (obstruction != null) {
            digger.dig(obstruction);   // faces + breaks the block (halts the body)
            // Baritone walkWhileBreaking (TRAVERSE only): keep approaching + sprint while
            // the front block breaks — but only if neither break cell (dest feet + head) is
            // something to avoid walking into, and we aren't already pressed against the
            // block (Chebyshev dist >= 0.83).
            if (mv.kind == Movement.Kind.TRAVERSE
                    && !BlockHelper.avoidWalkingInto(player.level(), mv.dest)
                    && !BlockHelper.avoidWalkingInto(player.level(), mv.dest.above())
                    && chebyshevDistTo(mv.dest) >= 0.83) {
                player.zza = 1.0f;
                player.setSprinting(speed >= 1.0);
            }
            return Status.RUNNING;
        }

        // 2) Place the scaffold floor under dest, if this move needs one — the live
        //    "edge sneak" maneuver (Baritone bridge feel): hold sneak, edge to the
        //    rim, look at the support face, place. Drives the body forward toward the
        //    placement itself, so the bridge is laid as it leans out, not teleport-popped.
        if (mv.toPlace != null && !placedThisMove) {
            if (placeManeuver == null) {
                placeManeuver = new PlaceManeuver(player, mv.toPlace, this::scaffoldSlot,
                        () -> BlockHelper.canWalkOn(player.level(), mv.toPlace));
            }
            switch (placeManeuver.tick()) {
                case DONE -> {
                    placedThisMove = true;
                    placeManeuver = null;
                }
                case FAILED -> {
                    placeManeuver = null;
                    return Status.NEEDS_REPLAN;   // out of blocks / no angle → replan
                }
                case RUNNING -> {
                    return Status.RUNNING;
                }
            }
        }

        // 3) Drive toward dest by movement kind — per-Movement updateState,
        //    mirroring Baritone's input-forcing timing.
        if (arrived(mv)) {
            advance();
            return Status.RUNNING;
        }
        drive(mv);
        // Baritone Movement.update universal liquid float (runs after EVERY movement's
        // updateState): if our feet cell is liquid and we're below dest.y+0.6, press jump
        // (→ jumpInLiquid buoyancy). This is the single framework-level mechanism that
        // keeps the body riding the water surface across ALL kinds — without it the body
        // sinks between per-move depth-corrections and porpoises.
        if (!player.level().getBlockState(player.blockPosition()).getFluidState().isEmpty()
                && player.getY() < mv.dest.getY() + 0.6) {
            InputDriver.jump(player);
        }
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

    /** Chebyshev (max-axis) horizontal distance to a cell centre — Baritone's
     *  walk-while-break "pressed against the block" gate uses this, not Euclidean. */
    private double chebyshevDistTo(BlockPos cell) {
        return Math.max(Math.abs((cell.getX() + 0.5) - player.getX()),
                        Math.abs((cell.getZ() + 0.5) - player.getZ()));
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
        openDoorsForMove(mv);            // a shut wooden door/gate ahead → open it, don't break it
        Vec3 dest = Vec3.atBottomCenterOf(mv.dest);
        // Baritone never sprints in water, nor when about to run into a hazard just
        // past the destination (it could carry us into lava/cactus/etc).
        boolean sprintBase = speed >= 1.0 && !player.isInWater() && !hazardJustPast(mv);
        switch (mv.kind) {
            case TRAVERSE -> {
                // Baritone MovementTraverse: correct DEPTH before advancing. If our feet
                // aren't on the destination's Y level (bobbing while swimming across water,
                // or sunk into a dip), do NOT move forward this tick — only rise (JUMP) if
                // we're below it; if we've popped ABOVE it, do nothing and let it settle.
                // Moving forward only happens once we're actually on the lane, which is
                // what keeps a water-surface crossing stable instead of porpoising.
                int feetY = player.blockPosition().getY();
                if (feetY != mv.dest.getY()) {
                    InputDriver.halt(player);
                    // Below the lane → rise. On LAND that's a step-up hop; in LIQUID the
                    // universal liquid-float jump (in tick()) already does the rising, so
                    // don't add a second impulse here (our jump() isn't an idempotent flag).
                    if (feetY < mv.dest.getY()
                            && player.level().getBlockState(player.blockPosition()).getFluidState().isEmpty()) {
                        InputDriver.jump(player);
                    }
                } else {
                    // On the lane: advance. Don't sprint across a floor we just placed
                    // (Baritone wasTheBridgeBlockAlwaysThere).
                    InputDriver.stepToward(player, dest, sprintBase && mv.toPlace == null);
                }
            }
            // Only sprint a diagonal when both cut corners are clear (Baritone sprint() corner check).
            case DIAGONAL -> InputDriver.stepToward(player, dest, sprintBase && diagonalCornersClear(mv));
            case ASCEND -> driveAscend(mv);
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
            case DESCEND -> driveDescend(mv);
            case FALL -> driveFall(mv);
            case DIG_DOWN -> {
                // Phase 1 breaks the floor; here we just keep centred over the column so
                // gravity drops us straight onto the dug cell (Baritone MovementDownward
                // recentres / moveTowards the break cell rather than free-drifting).
                if (horizontalDistTo(mv.dest) > 0.2) {
                    InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.dest), false);
                } else {
                    InputDriver.halt(player);
                }
            }
        }
    }

    /**
     * Step down one block (Baritone MovementDescend.updateState): aim one block past
     * dest (fakeDest) for the first ~20 ticks WHILE still near the start
     * (fromStart&lt;1.25), to carry momentum off the ledge; then aim at dest. Stop
     * pushing once horizontally on dest so gravity settles us cleanly.
     */
    private void driveDescend(Movement mv) {
        if (descendSafeMode(mv)) {
            // Baritone safeMode: a slowed, straight-in approach to a 0.17/0.83 weighted
            // point with NO fakeDest momentum — avoids overshooting into a wall / hazard
            // and the skip-to-ascend glitch.
            double dx = (mv.src.getX() + 0.5) * 0.17 + (mv.dest.getX() + 0.5) * 0.83;
            double dz = (mv.src.getZ() + 0.5) * 0.17 + (mv.dest.getZ() + 0.5) * 0.83;
            InputDriver.stepToward(player, new Vec3(dx, mv.dest.getY(), dz), false);
            return;
        }
        double ab = horizontalDistTo(mv.dest);
        if (!player.blockPosition().equals(mv.dest) || ab > 0.25) {
            Vec3 aim = (ticksOnCurrent < 20 && horizontalDistTo(mv.src) < 1.25)
                    ? Vec3.atBottomCenterOf(new BlockPos(
                            2 * mv.dest.getX() - mv.src.getX(),
                            mv.dest.getY(),
                            2 * mv.dest.getZ() - mv.src.getZ()))
                    : Vec3.atBottomCenterOf(mv.dest);
            InputDriver.stepToward(player, aim, false);
        } else {
            InputDriver.halt(player);
        }
    }

    /** Baritone MovementDescend.safeMode: a hazard just past dest (sprint-overshoot risk)
     *  OR the skip-to-ascend overshoot-glitch geometry (a wall at foot level with air above). */
    private boolean descendSafeMode(Movement mv) {
        if (hazardJustPast(mv)) return true;
        int dx = mv.dest.getX() - mv.src.getX();
        int dz = mv.dest.getZ() - mv.src.getZ();
        if (dx == 0 && dz == 0) return false;
        BlockPos into = mv.dest.offset(dx, 0, dz);
        return !BlockHelper.canWalkThrough(player.level(), into)
                && BlockHelper.canWalkThrough(player.level(), into.above())
                && BlockHelper.canWalkThrough(player.level(), into.above(2));
    }

    /** Something to avoid in the cell(s) one step PAST the destination — the block we'd
     *  carry into if we over-committed. Baritone's descend safeMode + sprint-suppression
     *  use {@code avoidWalkingInto} (any fluid + the hazard block set) for exactly this. */
    private boolean hazardJustPast(Movement mv) {
        int dx = Integer.signum(mv.dest.getX() - mv.src.getX());
        int dz = Integer.signum(mv.dest.getZ() - mv.src.getZ());
        if (dx == 0 && dz == 0) return false;
        BlockPos into = mv.dest.offset(dx, 0, dz);
        for (int y = 0; y <= 2; y++) {
            if (BlockHelper.avoidWalkingInto(player.level(), into.above(y))) return true;
        }
        return false;
    }

    /** Both diagonal cut-corners clear (Baritone MovementDiagonal.sprint corner check). */
    private boolean diagonalCornersClear(Movement mv) {
        BlockPos cornerA = new BlockPos(mv.src.getX(), mv.src.getY(), mv.dest.getZ());
        BlockPos cornerB = new BlockPos(mv.dest.getX(), mv.src.getY(), mv.src.getZ());
        return BlockHelper.canWalkThrough(player.level(), cornerA)
                && BlockHelper.canWalkThrough(player.level(), cornerA.above())
                && BlockHelper.canWalkThrough(player.level(), cornerB)
                && BlockHelper.canWalkThrough(player.level(), cornerB.above());
    }

    /**
     * Multi-block fall (Baritone MovementFall.updateState, minus the water-bucket
     * MLG which needs a bucket in hand): unlike a descend it does NOT carry momentum
     * — it RECENTRES on the landing cell so it doesn't clip a wall, sneaking while
     * fast-falling ({@code |Δy|>0.4}) to kill horizontal drift and land cleanly.
     */
    private void driveFall(Movement mv) {
        var v = player.getDeltaMovement();
        double cx = mv.dest.getX() + 0.5;
        double cz = mv.dest.getZ() + 0.5;
        // Look ahead by one tick of velocity, like Baritone, to anticipate the drift.
        boolean offCentre = Math.abs(player.getX() + v.x - cx) > 0.1
                || Math.abs(player.getZ() + v.z - cz) > 0.1;
        if (offCentre) {
            // Sneak while fast-falling to brake the horizontal drift toward centre.
            player.setShiftKeyDown(!player.onGround() && Math.abs(v.y) > 0.4);
            InputDriver.lookAt(player, fallAim(mv));   // landing cell, biased off an adjacent ladder
            player.zza = 1.0f;
            player.xxa = 0.0f;
            player.setSprinting(false);
        } else {
            player.setShiftKeyDown(false);
            InputDriver.halt(player);
        }
    }

    /** Aim at the landing cell, nudged 0.125 away from an adjacent ladder/vine so the
     *  fall doesn't grab it mid-drop (Baritone MovementFall.avoid). */
    private Vec3 fallAim(Movement mv) {
        Vec3 c = Vec3.atCenterOf(mv.dest);
        for (Direction d : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            var s = player.level().getBlockState(mv.dest.relative(d));
            if (s.is(net.minecraft.world.level.block.Blocks.LADDER)
                    || s.is(net.minecraft.world.level.block.Blocks.VINE)) {
                return c.add(-d.getStepX() * 0.125, 0.0, -d.getStepZ() * 0.125);
            }
        }
        return c;
    }

    /**
     * Step up one block (Baritone MovementAscend.updateState). Drive forward toward
     * dest, then JUMP when the body is ALIGNED to the move axis (small lateral
     * drift) and either the head is clear or we're close enough — critically NOT
     * gated on forward speed, so hitting the step (which zeroes forward speed) still
     * triggers the jump instead of stalling against the wall.
     */
    private void driveAscend(Movement mv) {
        InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.dest), false);
        if (player.blockPosition().equals(mv.src.above())) {
            return;   // already airborne off the step
        }
        int xAxis = Math.abs(mv.src.getX() - mv.dest.getX()); // 0 or 1
        int zAxis = Math.abs(mv.src.getZ() - mv.dest.getZ()); // 0 or 1
        double px = player.getX();
        double pz = player.getZ();
        double flatDistToNext = xAxis * Math.abs((mv.dest.getX() + 0.5) - px)
                + zAxis * Math.abs((mv.dest.getZ() + 0.5) - pz);
        double sideDist = zAxis * Math.abs((mv.dest.getX() + 0.5) - px)
                + xAxis * Math.abs((mv.dest.getZ() + 0.5) - pz);
        var dm = player.getDeltaMovement();
        double lateralMotion = xAxis * dm.z + zAxis * dm.x;   // drift perpendicular to the move axis
        if (Math.abs(lateralMotion) > 0.1) {
            return;   // still drifting sideways — wait until aligned
        }
        if (headBonkClear(mv.src)) {
            InputDriver.jump(player);   // head's clear above — jump now (InputDriver gates onGround)
            return;
        }
        if (flatDistToNext > 1.2 || sideDist > 0.2) {
            return;   // too far / off-axis — would bonk an adjacent block
        }
        InputDriver.jump(player);
    }

    /** Baritone headBonkClear: the four horizontals above src+2 are passable, so a
     *  jump won't smack the body's head into a ceiling block. */
    private boolean headBonkClear(BlockPos src) {
        BlockPos up2 = src.above(2);
        for (Direction d : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (!BlockHelper.canWalkThrough(player.level(), up2.relative(d))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Block-tower pillar (Baritone MovementPillar): sneak so we never step off the
     * column, stay centred, jump from the ground, and at the apex place a block in
     * the cell we just left so we land one higher.
     */
    private void drivePillar(Movement mv) {
        // Swim straight up a water column (Baritone MovementPillar water branch, verbatim):
        // aim at the destination cell's centre and press forward ONLY when off-centre by
        // >0.2 (just to recentre) — buoyancy does the actual rising. No sneak, no place.
        if (BlockHelper.isWater(player.level(), mv.src)) {
            player.setShiftKeyDown(false);
            InputDriver.lookAt(player, Vec3.atCenterOf(mv.dest));
            double cx = mv.dest.getX() + 0.5;
            double cz = mv.dest.getZ() + 0.5;
            player.zza = (Math.abs(player.getX() - cx) > 0.2 || Math.abs(player.getZ() - cz) > 0.2)
                    ? 1.0f : 0.0f;
            player.xxa = 0.0f;
            player.setSprinting(false);
            // The rise itself comes from the universal liquid-float jump in tick() — Baritone's
            // pillar water branch likewise presses no jump here, it relies on Movement.update.
            return;
        }
        player.setShiftKeyDown(true);   // sneak: never step off the column
        // Recentre on the column rather than walking off it.
        if (horizontalDistTo(mv.src) > 0.17) {
            InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.src), false);
        } else {
            InputDriver.halt(player);
            // Baritone MovementPillar: jump only when nearly still AND still BELOW the
            // destination — stop jumping once we've reached the top (`y < dest.y`).
            if (player.onGround() && horizontalSpeedSqr() < 0.0025
                    && player.getY() < mv.dest.getY()) {
                InputDriver.jump(player);
            }
        }
        // Once risen above the target foot height (the apex), place the block under
        // our feet against the block below — looking down at it, like a real player
        // (Baritone: CLICK_RIGHT when crouching + looking at the block below + y > dest.y+0.1).
        if (player.getY() > mv.dest.getY() + 0.1) {
            placeUnderfoot(mv.src);
        }
    }

    /** Place a scaffold block at {@code cell} against the solid block directly below it
     *  — the pillar apex place, performed natively (look down + useItemOn on the up-face). */
    private void placeUnderfoot(BlockPos cell) {
        int slot = scaffoldSlot();
        if (slot < 0) return;
        if (!Placement.canPlaceAgainst(player.level(), cell.below())) return;
        InputDriver.lookAt(player, Vec3.atBottomCenterOf(cell));   // look straight down at the support's top
        // Baritone crouch-confirm: only place once actually crouching — the sneak takes
        // effect the tick AFTER we request it, and isCrouching() is that "took effect" signal.
        if (!player.isCrouching()) return;
        BlockHitResult hit = Placement.resolve(player, cell, true);  // honest raycast only — no fabricated hit
        if (hit == null) return;
        player.setItemInHand(InteractionHand.MAIN_HAND, player.getInventory().getItem(slot));
        Interaction.useBlock(player, hit, InteractionHand.MAIN_HAND).tick();
    }

    /**
     * Open any shut wooden door / fence gate in this movement's path (the dest
     * cell + the head cell above it) by hand — Baritone's alternative to breaking
     * it. The path planner already treats these as passable (BlockHelper), so they
     * never enter {@code toBreak}; here we just right-click them open as we arrive.
     */
    private void openDoorsForMove(Movement mv) {
        openDoorAt(mv.dest);
        openDoorAt(mv.dest.above());
    }

    private void openDoorAt(BlockPos cell) {
        BlockState state = player.level().getBlockState(cell);
        if (!BlockHelper.isOpenableDoor(state) || BlockHelper.isDoorOpen(state)) {
            return;
        }
        // Not sneaking (drive() cleared shift) so the door's own use() fires and
        // opens it, rather than placing a held block against it.
        Vec3 centre = Vec3.atCenterOf(cell);
        InputDriver.lookAt(player, centre);
        player.gameMode.useItemOn(player, player.level(),
                player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND,
                new BlockHitResult(centre, Direction.UP, cell, false));
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
        // Baritone gates EVERY cost-based cancel on canCancel = movement.safeToCancel():
        // a movement mid-commit (airborne, mid-jump, mid-place, bridging over air) must
        // not be abandoned to a replan, or the body is dropped into a bad state. While
        // not cancellable, let the move finish; the movement timeout still bounds a stall.
        if (!safeToCancel(cur)) {
            return null;
        }
        // Movements whose OWN execution mutates the world — pillar places a block
        // underfoot, dig-down breaks the floor — would self-trigger COST_INF the
        // instant they act (regeneration no longer emits them). Skip live re-costing
        // for those; the movement timeout still guards a genuine stall. Movements
        // that only consume the world (break an obstruction) get cheaper, never
        // INF, so they're safe to verify.
        if (!selfMutating(cur.kind)) {
            double liveCur = recost(fresh, cur);
            if (liveCur >= ActionCosts.COST_INF) {
                return Status.NEEDS_REPLAN;
            }
            if (liveCur - cur.cost > PathSettings.MAX_COST_INCREASE) {
                return Status.NEEDS_REPLAN;
            }
        }
        if (costCheckIndex != index) {
            costCheckIndex = index;
            int last = path.movements.size() - 1;
            int end = Math.min(last, index + PathSettings.COST_VERIFICATION_LOOKAHEAD);
            for (int i = index + 1; i <= end; i++) {
                Movement ahead = path.movements.get(i);
                if (!selfMutating(ahead.kind)
                        && recost(fresh, ahead) >= ActionCosts.COST_INF) {
                    return Status.NEEDS_REPLAN;
                }
            }
        }
        return null;
    }

    /**
     * Baritone PathExecutor's {@code canCancel = movement.safeToCancel()} gate, mapped
     * to our movement kinds + live body state. A movement that is mid-commit must not
     * be cancelled (replanned) — doing so drops the body off a fall, fails a jump, or
     * strands it mid-bridge. Mirrors each Baritone Movement.safeToCancel override:
     * <ul>
     *   <li>FALL — safe only before stepping off the edge (still at {@code src});</li>
     *   <li>PARKOUR — only on the takeoff (0th) tick, no momentum knowledge after;</li>
     *   <li>ASCEND — not while a step block was just placed;</li>
     *   <li>TRAVERSE — a sneak-bridge over air can't be abandoned until its floor exists;</li>
     *   <li>others (DIAGONAL/DESCEND/PILLAR/DIG_DOWN) — base {@code true}.</li>
     * </ul>
     */
    private boolean safeToCancel(Movement mv) {
        switch (mv.kind) {
            case FALL:
                return player.blockPosition().equals(mv.src);
            case PARKOUR:
                return ticksOnCurrent == 0;
            case ASCEND:
                // Baritone: unsafe once we've STARTED placing the step block
                // (ticksWithoutPlacement>0) — for us, once the place maneuver began or finished.
                return mv.toPlace == null || (placeManeuver == null && !placedThisMove);
            case TRAVERSE:
                return mv.toPlace == null
                        || BlockHelper.canWalkOn(player.level(), mv.dest.below());
            case DIAGONAL:
                return diagonalSafeToCancel(mv);
            default:
                return true;
        }
    }

    /** Baritone MovementDiagonal.safeToCancel: safe at the start cell, or when both cut
     *  corners have a floor; if we're cornering through an unwalkable corner cell, only
     *  safe when a block actually supports us (one of the four 0.25 offsets below). */
    private boolean diagonalSafeToCancel(Movement mv) {
        net.minecraft.world.level.Level level = player.level();
        BlockPos feet = player.blockPosition();
        if (feet.equals(mv.src)) return true;
        BlockPos floorA = new BlockPos(mv.src.getX(), mv.src.getY() - 1, mv.dest.getZ());
        BlockPos floorB = new BlockPos(mv.dest.getX(), mv.src.getY() - 1, mv.src.getZ());
        if (BlockHelper.canWalkOn(level, floorA) && BlockHelper.canWalkOn(level, floorB)) {
            return true;
        }
        BlockPos cornerA = new BlockPos(mv.src.getX(), mv.src.getY(), mv.dest.getZ());
        BlockPos cornerB = new BlockPos(mv.dest.getX(), mv.src.getY(), mv.src.getZ());
        if (feet.equals(cornerA) || feet.equals(cornerB)) {
            double off = 0.25;
            double x = player.getX(), y = player.getY() - 1, z = player.getZ();
            return BlockHelper.canWalkOn(level, BlockPos.containing(x + off, y, z + off))
                    || BlockHelper.canWalkOn(level, BlockPos.containing(x + off, y, z - off))
                    || BlockHelper.canWalkOn(level, BlockPos.containing(x - off, y, z + off))
                    || BlockHelper.canWalkOn(level, BlockPos.containing(x - off, y, z - off));
        }
        return true;
    }

    /** Kinds whose execution changes the world such that regenerating them mid-move
     *  would spuriously report them gone (place-under / break-floor). */
    private static boolean selfMutating(Movement.Kind kind) {
        return kind == Movement.Kind.PILLAR
                || kind == Movement.Kind.DIG_DOWN;
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

    /**
     * Has the move completed? Feet at dest, or — for a walk — overshot 1–2 cells past
     * it at the same height (Baritone {@code overshootTraverse}: a sprint can carry the
     * body a cell or two beyond the target, which still counts as arrived).
     */
    private boolean arrived(Movement mv) {
        BlockPos feet = player.blockPosition();
        if (mv.kind == Movement.Kind.PILLAR) {
            // Water swim-up: Baritone's pillar water branch succeeds on feet==dest with NO
            // block check (you're swimming, nothing is placed).
            if (BlockHelper.isWater(player.level(), mv.src)) {
                return feet.equals(mv.dest);
            }
            // Dry tower: Baritone success = feet at dest AND blockIsThere (the placed block
            // exists, canWalkOn(src)). WITHOUT the block check, the jump APEX — feet
            // momentarily at dest.y mid-air, before placeUnderfoot has placed anything —
            // false-arrives, advances the index, then the body falls back with nothing
            // placed and churns forever. The block check holds arrival until we've placed.
            return feet.equals(mv.dest) && BlockHelper.canWalkOn(player.level(), mv.src);
        }
        if (mv.kind == Movement.Kind.DESCEND) {
            // Baritone MovementDescend success: feet at dest OR the overshoot fakeDest, AND
            // settled within 0.5 of dest.y (or dest is liquid) — don't advance while still
            // falling through the destination cell.
            BlockPos fakeDest = new BlockPos(
                    2 * mv.dest.getX() - mv.src.getX(), mv.dest.getY(), 2 * mv.dest.getZ() - mv.src.getZ());
            if (!feet.equals(mv.dest) && !feet.equals(fakeDest)) return false;
            return !player.level().getBlockState(mv.dest).getFluidState().isEmpty()
                    || player.getY() - mv.dest.getY() < 0.5;
        }
        if (mv.kind == Movement.Kind.FALL) {
            // Baritone MovementFall success: feet at dest AND settled within 0.094 of dest.y
            // (lilypad tolerance); or landed in water and no longer sinking (no water-bucket
            // clutch at default settings).
            if (!feet.equals(mv.dest)) return false;
            if (!player.level().getBlockState(mv.dest).getFluidState().isEmpty()) {
                return player.getDeltaMovement().y >= 0.0;
            }
            return player.getY() - mv.dest.getY() < 0.094;
        }
        if (mv.kind == Movement.Kind.TRAVERSE) {
            // Baritone: SUCCESS only once the floor under dest exists (isTheBridgeBlockThere
            // = canWalkOn(dest.below)) — don't declare a bridge traverse done before the
            // placed floor is there. Then feet==dest, or a 1-2 cell sprint overshoot in the
            // move direction (Baritone overshootTraverse).
            if (!BlockHelper.canWalkOn(player.level(), mv.dest.below())) return false;
            if (feet.equals(mv.dest)) return true;
            int dx = Integer.signum(mv.dest.getX() - mv.src.getX());
            int dz = Integer.signum(mv.dest.getZ() - mv.src.getZ());
            if (dx != 0 || dz != 0) {
                BlockPos one = mv.dest.offset(dx, 0, dz);
                if (feet.equals(one) || feet.equals(one.offset(dx, 0, dz))) return true;
            }
            return false;
        }
        if (mv.kind == Movement.Kind.ASCEND) {
            // Baritone MovementAscend success: feet==dest OR one cell further horizontally
            // (the jump can carry us a cell past dest).
            if (feet.equals(mv.dest)) return true;
            int dx = mv.dest.getX() - mv.src.getX();
            int dz = mv.dest.getZ() - mv.src.getZ();
            return feet.equals(mv.dest.offset(dx, 0, dz));
        }
        // DIAGONAL (Baritone: feet==dest only, no overshoot), DIG_DOWN, PARKOUR.
        return feet.equals(mv.dest);
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

    /** The inventory slot of a scaffold block (cobble/dirt/…), or -1 if none. */
    private int scaffoldSlot() {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (NavContext.isScaffold(inv.getItem(i))) return i;
        }
        return -1;
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
        // Off-path watchdog (Baritone PathExecutor.onTick + closestPathPos): distance to the
        // closest valid cell of the WHOLE path — not just the current movement — so the body
        // being near any part of the route doesn't read as off-path. Two bands: >2 blocks
        // counts toward MAX_TICKS_AWAY before giving up, >3 cancels immediately.
        double bestSq = Double.MAX_VALUE;
        for (Movement m : path.movements) {
            for (BlockPos vp : m.validPositions()) {
                double d = player.distanceToSqr(Vec3.atCenterOf(vp));
                if (d < bestSq) bestSq = d;
            }
        }
        // Soft band first (matches Baritone's order), then the immediate hard band.
        if (possiblyOffPath(cur, bestSq, SOFT_DIST_SQR)) {
            if (++ticksAway > PathSettings.MAX_TICKS_AWAY) {
                return Status.NEEDS_REPLAN;
            }
        } else {
            ticksAway = 0;
        }
        if (possiblyOffPath(cur, bestSq, HARD_DIST_SQR)) {
            return Status.NEEDS_REPLAN;
        }
        return null;
    }

    /** Baritone PathExecutor.possiblyOffPath: are we further than {@code leniencySq} from the
     *  path? With a mid-FALL carve-out — falling you're far in Y from both ends but not off
     *  path, so judge a fall by the FLAT (XZ) distance to its landing cell instead. */
    private boolean possiblyOffPath(Movement cur, double bestSq, double leniencySq) {
        if (bestSq <= leniencySq) return false;
        if (cur.kind == Movement.Kind.FALL) {
            double dx = (cur.dest.getX() + 0.5) - player.getX();
            double dz = (cur.dest.getZ() + 0.5) - player.getZ();
            return (dx * dx + dz * dz) >= leniencySq;
        }
        return true;
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
        digger.cancel();          // a partial dig belongs to the move we just left
        placedThisMove = false;
        if (placeManeuver != null) {
            placeManeuver.stop();
            placeManeuver = null;
        }
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
        digger.cancel();
        if (placeManeuver != null) {
            placeManeuver.stop();
            placeManeuver = null;
        }
        InputDriver.halt(player);
        // Release sneak too — pillar/place hold it every tick; without this it lingers
        // through a mid-path replan's planning ticks (the next path's drive() clears it,
        // but only once it starts). Mirrors PlayerNav.stop().
        player.setShiftKeyDown(false);
    }
}
