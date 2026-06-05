package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.Path;
import com.dwinovo.animus.pathing.movement.Movement;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.task.tasks.BlockMiningProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Drives an entity along a computed {@link Path}, one {@link Movement} at a
 * time, executing each primitive's terrain edits before walking.
 *
 * <h2>Re-localization first (the robustness core)</h2>
 * Every tick begins by matching the entity's actual feet against the
 * {@link Movement#validPositions() valid feet-sets} of the current and nearby
 * movements (Baritone's {@code PathExecutor} model). If an external force
 * pushed the entity backward, ahead, or it overshot/fell, we resync the path
 * <em>index</em> in place instead of throwing the path away. Only when the
 * entity is genuinely off every nearby movement — far off, or off for a while
 * — do we ask the owning goal to replan. This single mechanism replaces the old
 * pile of stuck timers / deviation margins / best-distance trackers.
 *
 * <h2>Drive</h2>
 * Steered moves (TRAVERSE/ASCEND/DESCEND/DIAGONAL) hand a wanted-position to the
 * vanilla {@code MoveControl}. Input-driven moves ({@link Movement#inputDriven()}
 * — PILLAR) are driven by explicit jump/place cycles at the entity's current
 * height so MoveControl never auto-jumps and fights us.
 *
 * <h2>Replan signalling</h2>
 * {@link #tick()} returns a {@link Status}: {@code ARRIVED} ends the task,
 * {@code NEEDS_REPLAN} recomputes a fresh path (off-path, ran out of
 * scaffolding, world changed), {@code FAILED} is unrecoverable.
 */
public final class PathExecutor {

    /** Verbose pathing diagnostics to the {@code Animus} logger at INFO. */
    public static boolean VERBOSE = true;

    public enum Status { RUNNING, ARRIVED, NEEDS_REPLAN, FAILED }

    private enum Phase { PREPARE_BREAK, PREPARE_PLACE, MOVE, PILLAR_UP }

    /** Movements scanned each side of the current index when resyncing the feet. */
    private static final int RESYNC_SCAN = 8;
    /** Consecutive ticks tolerated off-path (but near it) before we replan. */
    private static final int AWAY_BUDGET = 60;
    /** >3 blocks off the current movement → definitively shoved off, replan now. */
    private static final double HARD_DIST_SQR = 9.0;
    /** Horizontal arrival tolerance² (≈0.6 block). */
    private static final double ARRIVE_HORIZ_SQR = 0.36;

    private final AnimusEntity entity;
    private final Path path;
    private final double speed;

    private int index = 0;
    private Phase phase = Phase.PREPARE_BREAK;

    // Break sub-state.
    private final BlockMiningProgress mining;
    private int breakIndex = 0;
    private boolean miningStarted = false;

    /** Ticks spent on the current movement — a stall backstop scaled to its cost. */
    private int ticksOnCurrent = 0;
    /** Consecutive ticks the feet matched no nearby movement (off-path). */
    private int ticksAway = 0;

    // Pillar sub-state: each cycle pillars from the entity's ACTUAL feet height,
    // robust to having landed a block off from the plan.
    private int pillarBaseY;
    private boolean pillarPlacedThisCycle = false;

    public PathExecutor(AnimusEntity entity, Path path, double speed) {
        this.entity = entity;
        this.path = path;
        this.speed = speed;
        this.mining = new BlockMiningProgress(entity);
        plog("new path: " + path.movements.size() + " moves, partial=" + path.partial
                + ", from=" + entity.blockPosition() + " kinds=" + summariseKinds());
    }

    public Status tick() {
        if (path.isEmpty()) {
            // Nothing to walk; the path was already at (or couldn't leave) start.
            return path.partial ? Status.NEEDS_REPLAN : Status.ARRIVED;
        }
        if (index >= path.movements.size()) {
            // Walked the whole path. If it was partial, ask for a fresh search
            // toward the real goal; otherwise we've arrived.
            return path.partial ? Status.NEEDS_REPLAN : Status.ARRIVED;
        }

        // 1) RE-LOCALIZE: resync the index to where the entity actually is, or
        //    signal a replan if it's genuinely off-path.
        Status reloc = relocalize();
        if (reloc != null) return reloc;
        if (index >= path.movements.size()) {
            return path.partial ? Status.NEEDS_REPLAN : Status.ARRIVED;
        }

        Movement mv = path.movements.get(index);

        // Per-second heartbeat: position / phase / node / counters.
        if (VERBOSE && entity.level().getGameTime() % 20 == 0) {
            Vec3 p = entity.position();
            plog(String.format(
                    "t=%d phase=%s idx=%d/%d kind=%s pos=(%.2f,%.2f,%.2f) node=(%d,%d,%d) onGround=%b on=%d away=%d",
                    entity.level().getGameTime(), phase, index, path.movements.size(), mv.kind,
                    p.x, p.y, p.z, mv.dest.getX(), mv.dest.getY(), mv.dest.getZ(),
                    entity.onGround(), ticksOnCurrent, ticksAway));
        }

        // 2) STALL BACKSTOP: if we've spent far longer on this movement than its
        //    planned cost (in ticks), something the re-localization can't see is
        //    wrong (walking into a wall that should be clear) — replan.
        ticksOnCurrent++;
        int budget = (int) (mv.cost * 4) + 60;
        if (ticksOnCurrent > budget) {
            plog(String.format("REPLAN stall: %dt on idx=%d kind=%s (budget=%d)",
                    ticksOnCurrent, index, mv.kind, budget));
            return Status.NEEDS_REPLAN;
        }

        // 3) DRIVE.
        return switch (phase) {
            case PREPARE_BREAK -> tickBreak(mv);
            case PREPARE_PLACE -> tickPlace(mv);
            case MOVE -> tickMove(mv);
            case PILLAR_UP -> tickPillar(mv);
        };
    }

    // ---- RE-LOCALIZATION ----

    /**
     * Match the entity's real feet against nearby movements' valid feet-sets.
     * Returns {@code null} to keep executing (possibly after resyncing the
     * index), or {@link Status#NEEDS_REPLAN} when the entity is genuinely
     * off-path.
     */
    private Status relocalize() {
        BlockPos feet = entity.blockPosition();
        Movement cur = path.movements.get(index);
        if (cur.validPositions().contains(feet)) {
            ticksAway = 0;
            return null;
        }
        // Pushed/teleported backward, or fell onto an earlier node: scan back.
        for (int i = index - 1; i >= Math.max(0, index - RESYNC_SCAN); i--) {
            if (path.movements.get(i).validPositions().contains(feet)) {
                jumpToIndex(i, "pushed back");
                return null;
            }
        }
        // Overshot / pushed ahead / parkoured past: scan forward.
        int last = path.movements.size() - 1;
        for (int i = index + 1; i <= Math.min(last, index + RESYNC_SCAN); i++) {
            if (path.movements.get(i).validPositions().contains(feet)) {
                jumpToIndex(i, "overshoot");
                return null;
            }
        }
        // Off every nearby movement. Tolerate briefly (the drive below tries to
        // walk back onto the path); replan if far off, or off for too long.
        ticksAway++;
        double distSqr = distSqToMove(cur);
        if (distSqr > HARD_DIST_SQR) {
            plog(String.format("REPLAN off-path: distSq=%.2f from idx=%d (shoved off)", distSqr, index));
            return Status.NEEDS_REPLAN;
        }
        if (ticksAway > AWAY_BUDGET) {
            plog(String.format("REPLAN off-path: %dt away from idx=%d", ticksAway, index));
            return Status.NEEDS_REPLAN;
        }
        return null;
    }

    /** Horizontal+vertical dist² from the entity to the nearer of the move's src/dest cells. */
    private double distSqToMove(Movement mv) {
        double a = entity.distanceToSqr(Vec3.atBottomCenterOf(mv.src));
        double b = entity.distanceToSqr(Vec3.atBottomCenterOf(mv.dest));
        return Math.min(a, b);
    }

    /** Resync to movement {@code i}, restarting its prepare/move state. */
    private void jumpToIndex(int i, String why) {
        if (miningStarted && index < path.movements.size()) {
            Movement old = path.movements.get(index);
            if (breakIndex < old.toBreak.size()) {
                mining.cleanup(old.toBreak.get(breakIndex));
            }
        }
        plog("relocalize (" + why + "): idx " + index + " -> " + i
                + " kind=" + path.movements.get(i).kind);
        index = i;
        resetMoveState();
        ticksAway = 0;
    }

    // ---- PREPARE: break obstructions one at a time ----

    private Status tickBreak(Movement mv) {
        if (breakIndex >= mv.toBreak.size()) {
            // Pillar places mid-jump (own phase); everything else places its
            // floor block first, then walks.
            phase = (mv.kind == Movement.Kind.PILLAR) ? Phase.PILLAR_UP : Phase.PREPARE_PLACE;
            return Status.RUNNING;
        }
        BlockPos target = mv.toBreak.get(breakIndex);
        Level level = entity.level();

        // Block may already be gone (someone/something cleared it) — skip.
        if (BlockHelper.canWalkThrough(level, target)) {
            mining.cleanup(target);
            miningStarted = false;
            breakIndex++;
            return Status.RUNNING;
        }

        if (!miningStarted) {
            if (!mining.tryStart(target)) {
                // Became unmineable since planning — replan around it.
                plog("REPLAN: obstruction " + target + " became unmineable");
                return Status.NEEDS_REPLAN;
            }
            miningStarted = true;
        }

        BlockMiningProgress.Outcome outcome = mining.tick(target);
        if (outcome == BlockMiningProgress.Outcome.COMPLETED && mining.startState() != null) {
            // Count the obstruction we dug to clear the route (we broke it — a
            // BLOCK_GONE means someone else did, so it isn't tallied).
            entity.pathTally().addDug(mining.startState().getBlock());
            plog("dug obstruction " + mining.startState().getBlock() + " @ " + target);
        }
        switch (outcome) {
            case IN_PROGRESS -> { /* keep swinging */ }
            case COMPLETED, FAILED_BLOCK_GONE -> {
                mining.cleanup(target);
                miningStarted = false;
                breakIndex++;
            }
            case FAILED_OUT_OF_REACH -> {
                // Walk a touch closer by deferring to replanning.
                mining.cleanup(target);
                miningStarted = false;
                plog("REPLAN: obstruction " + target + " out of reach mid-dig");
                return Status.NEEDS_REPLAN;
            }
            case FAILED_BLOCK_CHANGED -> {
                mining.cleanup(target);
                miningStarted = false;
                plog("REPLAN: obstruction " + target + " changed mid-dig");
                return Status.NEEDS_REPLAN;
            }
        }
        return Status.RUNNING;
    }

    // ---- PREPARE: place the scaffolding floor block, if any ----

    private Status tickPlace(Movement mv) {
        if (mv.toPlace == null) {
            phase = Phase.MOVE;
            return Status.RUNNING;
        }
        Level level = entity.level();
        BlockPos at = mv.toPlace;

        if (!BlockHelper.isReplaceableForPlacement(level, at)) {
            // Already filled (or no longer placeable) — if it's now solid we
            // can just walk; otherwise replan.
            if (BlockHelper.canWalkOn(level, at)) {
                phase = Phase.MOVE;
                return Status.RUNNING;
            }
            plog("REPLAN: scaffold spot " + at + " not placeable and not walkable");
            return Status.NEEDS_REPLAN;
        }

        ItemStack stack = takeScaffold();
        if (stack == null) {
            // Ran out of scaffolding mid-path — replan; the fresh NavContext
            // will have hasScaffold == false and route around (or fail clean).
            plog("REPLAN: out of scaffolding for " + at);
            return Status.NEEDS_REPLAN;
        }
        BlockState state = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
        if (!level.setBlock(at, state, 3)) {
            plog("REPLAN: setBlock failed for scaffold @ " + at);
            return Status.NEEDS_REPLAN;
        }
        stack.shrink(1);
        entity.getInventory().setChanged();
        entity.pathTally().addPlaced(state.getBlock());
        plog("placed scaffold " + state.getBlock() + " @ " + at);
        phase = Phase.MOVE;
        return Status.RUNNING;
    }

    // ---- MOVE: walk/jump to the destination cell ----

    private Status tickMove(Movement mv) {
        Level level = entity.level();
        Vec3 center = new Vec3(mv.dest.getX() + 0.5, mv.dest.getY(), mv.dest.getZ() + 0.5);
        entity.getMoveControl().setWantedPosition(center.x, center.y, center.z, speed);
        entity.getLookControl().setLookAt(center.x, center.y + entity.getEyeHeight(), center.z);

        double dx = entity.getX() - center.x;
        double dz = entity.getZ() - center.z;
        double horizSqr = dx * dx + dz * dz;
        double dyToNode = entity.getY() - mv.dest.getY();

        // Step up onto an ASCEND's target block: jump only when grounded (one
        // jump per landing, never re-fired mid-air), still below the target, and
        // with head-room to rise. The step block itself was already placed in
        // PREPARE_PLACE, so there's no mid-air landing-block correction here.
        if (mv.kind == Movement.Kind.ASCEND
                && entity.onGround()
                && entity.getY() < mv.dest.getY() - 0.05
                && BlockHelper.canWalkThrough(level, entity.blockPosition().above(2))) {
            entity.getJumpControl().jump();
        }

        // Arrive once actually standing at the destination level: require
        // onGround (never advance mid-jump/mid-fall) plus a tight height window.
        if (horizSqr <= ARRIVE_HORIZ_SQR && entity.onGround() && Math.abs(dyToNode) <= 0.5) {
            advance();
        }
        return Status.RUNNING;
    }

    // ---- PILLAR_UP: jump straight up, place the floor block beneath mid-rise ----

    private Status tickPillar(Movement mv) {
        Level level = entity.level();
        double cx = mv.dest.getX() + 0.5;
        double cz = mv.dest.getZ() + 0.5;
        // Drive MoveControl toward the column at the entity's CURRENT height, not
        // the dest above us: a wantedY above makes vanilla MoveControl jump on
        // its own to "reach" it, fighting our explicit jump+place cycle. Rising
        // is entirely our explicit jump's job. Off-column pushes are caught by
        // re-localization (feet leave the {src,dest} column → off-path).
        entity.getMoveControl().setWantedPosition(cx, entity.getY(), cz, 0.2);
        entity.getLookControl().setLookAt(cx, mv.dest.getY() + 1.0, cz);

        double cdx = entity.getX() - cx;
        double cdz = entity.getZ() - cz;
        boolean centred = (cdx * cdx + cdz * cdz) <= 0.10;   // over the column (~0.3 block)

        // Reached the planned top — advance WITHOUT requiring onGround (the apex
        // is often airborne; feetY is floored so this only fires at/above target).
        if (entity.blockPosition().getY() >= mv.dest.getY() && centred) {
            advance();
            return Status.RUNNING;
        }

        if (entity.onGround()) {
            if (centred) {
                // Start one pillar cycle from our ACTUAL feet height.
                pillarBaseY = entity.blockPosition().getY();
                pillarPlacedThisCycle = false;
                entity.getJumpControl().jump();
            }
            // not centred → don't jump; let MoveControl centre us.
        } else if (!pillarPlacedThisCycle && entity.getY() >= pillarBaseY + 0.5) {
            // Airborne and cleared the base cell → drop a scaffold into it to land on.
            BlockPos base = new BlockPos(mv.dest.getX(), pillarBaseY, mv.dest.getZ());
            if (BlockHelper.canWalkOn(level, base)) {
                pillarPlacedThisCycle = true;   // already solid somehow
            } else if (BlockHelper.isReplaceableForPlacement(level, base)) {
                ItemStack stack = takeScaffold();
                if (stack == null) {
                    plog("REPLAN: out of scaffolding mid-pillar @ " + base);
                    return Status.NEEDS_REPLAN;
                }
                BlockState state = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
                if (!level.setBlock(base, state, 3)) {
                    plog("REPLAN: pillar setBlock failed @ " + base);
                    return Status.NEEDS_REPLAN;
                }
                stack.shrink(1);
                entity.getInventory().setChanged();
                entity.pathTally().addPlaced(state.getBlock());
                plog("placed pillar block " + state.getBlock() + " @ " + base);
                pillarPlacedThisCycle = true;
            }
        }
        return Status.RUNNING;
    }

    private void advance() {
        index++;
        resetMoveState();
        ticksAway = 0;
        if (index < path.movements.size()) {
            Movement next = path.movements.get(index);
            plog("advance -> idx " + index + "/" + path.movements.size()
                    + " kind=" + next.kind + " dest=" + next.dest);
        } else {
            plog("advance -> idx " + index + "/" + path.movements.size() + " (path end)");
        }
    }

    /** Reset the per-movement prepare/move bookkeeping (kept on index change). */
    private void resetMoveState() {
        phase = Phase.PREPARE_BREAK;
        breakIndex = 0;
        miningStarted = false;
        ticksOnCurrent = 0;
        pillarPlacedThisCycle = false;
    }

    /** Find and return a scaffolding stack from inventory (not yet shrunk), or null. */
    private ItemStack takeScaffold() {
        return NavContext.firstScaffoldItem(entity.getInventory());
    }

    /** Compact summary of the path's movement kinds for the new-path log. */
    private String summariseKinds() {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(path.movements.size(), 16);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(path.movements.get(i).kind);
        }
        if (path.movements.size() > n) sb.append(",…");
        return sb.append(']').toString();
    }

    private void plog(String msg) {
        if (VERBOSE) {
            Constants.LOG.info("[animus-path#{}] {}", entity.getId(), msg);
        }
    }

    /** True if this path stopped short of the goal (executor signals replan at its end). */
    public boolean isPartial() {
        return path.partial;
    }

    /** Feet position this path ends at — the root for precomputing the next segment. */
    public BlockPos pathEnd() {
        return path.end;
    }

    /** Movements left to execute (for deciding when to precompute the continuation). */
    public int remainingMovements() {
        return Math.max(0, path.movements.size() - index);
    }

    /** Release any in-progress mining overlay. Call when the task ends. */
    public void stop() {
        if (index < path.movements.size()) {
            Movement mv = path.movements.get(index);
            if (breakIndex < mv.toBreak.size()) {
                mining.cleanup(mv.toBreak.get(breakIndex));
            }
        }
        entity.getNavigation().stop();
    }
}
