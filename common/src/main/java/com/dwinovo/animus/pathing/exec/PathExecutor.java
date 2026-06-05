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
 * time, executing each primitive's terrain edits before walking. Per-tick
 * state machine:
 *
 * <pre>
 *   for each movement:
 *     PREPARE  → mine every toBreak block (tool-aware, drops to inventory)
 *              → place the scaffold block (toPlace) from inventory
 *     MOVE     → walk/jump toward dest center until arrived
 *   → done
 * </pre>
 *
 * <h2>Replan signalling</h2>
 * {@link #tick()} returns a {@link Status}. The owning goal reacts:
 * {@code ARRIVED} ends the task, {@code NEEDS_REPLAN} recomputes a fresh path
 * (e.g. ran out of scaffolding, world changed, or stuck), {@code FAILED} is
 * unrecoverable.
 *
 * <h2>Diagnostics</h2>
 * When {@link #VERBOSE} is on, the executor logs a {@code [animus-path#id]} line
 * once per second (position / phase / current node / stuck counter) plus every
 * jump, advance, replan, dig and place — used to diagnose stuck loops. Flip
 * {@code VERBOSE = false} to silence once an issue is understood.
 */
public final class PathExecutor {

    /** Verbose pathing diagnostics to the {@code Animus} logger at INFO. */
    public static boolean VERBOSE = true;

    public enum Status { RUNNING, ARRIVED, NEEDS_REPLAN, FAILED }

    private enum Phase { PREPARE_BREAK, PREPARE_PLACE, MOVE, PILLAR_UP }

    /** Ticks without progress toward the current node before we declare stuck/replan. */
    private static final int STUCK_TICKS = 40;
    /** Horizontal arrival tolerance² (≈0.6 block). */
    private static final double ARRIVE_HORIZ_SQR = 0.36;
    /**
     * If we end up this much farther (block²) from the current node than when the
     * move began, we've been shoved off-path (knocked into a pit, pushed by a
     * mob/piston) — replan immediately instead of waiting out the stuck window.
     */
    private static final double DEVIATION_MARGIN_SQR = 4.0;
    /** Minimum dist² improvement that counts as "made progress" toward the node. */
    private static final double PROGRESS_EPS = 0.01;

    private final AnimusEntity entity;
    private final Path path;
    private final double speed;

    private int index = 0;
    private Phase phase = Phase.PREPARE_BREAK;

    // Break sub-state.
    private final BlockMiningProgress mining;
    private int breakIndex = 0;
    private boolean miningStarted = false;

    // Stuck detection during MOVE: progress toward the current node, not raw
    // displacement (raw displacement is fooled by being pushed/jittered in place).
    private int stuckCounter = 0;
    private double bestDistSqToNode = Double.MAX_VALUE;
    private double moveStartDistSqToNode = -1.0;

    // Pillar sub-state. Self-correcting: each cycle pillars from the entity's
    // ACTUAL feet height (not the stale planned toPlace), so a fall/drift after
    // planning can't desync it. bestPillarY drives a vertical-progress stuck
    // check (the right axis for a pillar) instead of position displacement.
    private int pillarBaseY;
    private boolean pillarPlacedThisCycle = false;
    private double bestPillarY = Double.NEGATIVE_INFINITY;

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

        Movement mv = path.movements.get(index);
        // Per-second heartbeat: position / phase / node / stuck counter.
        if (VERBOSE && entity.level().getGameTime() % 20 == 0) {
            Vec3 p = entity.position();
            String best = bestDistSqToNode == Double.MAX_VALUE
                    ? "n/a" : String.format("%.2f", bestDistSqToNode);
            plog(String.format(
                    "t=%d phase=%s idx=%d/%d kind=%s pos=(%.2f,%.2f,%.2f) node=(%d,%d,%d) onGround=%b stuck=%d bestDistSq=%s",
                    entity.level().getGameTime(), phase, index, path.movements.size(), mv.kind,
                    p.x, p.y, p.z, mv.dest.getX(), mv.dest.getY(), mv.dest.getZ(),
                    entity.onGround(), stuckCounter, best));
        }
        return switch (phase) {
            case PREPARE_BREAK -> tickBreak(mv);
            case PREPARE_PLACE -> tickPlace(mv);
            case MOVE -> tickMove(mv);
            case PILLAR_UP -> tickPillar(mv);
        };
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
                // Walk a touch closer by deferring to MOVE replanning.
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

        if (mv.kind == Movement.Kind.ASCEND) {
            // Step up onto an ASCEND's target block — but only jump when grounded
            // (one jump per landing, never re-fired mid-air), still below the
            // target height, and with head-room to rise. Spamming jump() every
            // tick caused stair over-jumping and the ceiling-bounce-forever loop.
            if (entity.onGround()
                    && entity.getY() < mv.dest.getY() - 0.05
                    && BlockHelper.canWalkThrough(level, entity.blockPosition().above(2))) {
                entity.getJumpControl().jump();
                plog(String.format("JUMP ascend pos.y=%.2f nodeY=%d feet=%s",
                        entity.getY(), mv.dest.getY(), entity.blockPosition()));
            } else if (!entity.onGround() && horizSqr <= ARRIVE_HORIZ_SQR && dyToNode >= -0.1) {
                // Self-correcting landing block: we've jumped up and are over the
                // destination, but there's no block under it to land on (the plan
                // assumed a step that isn't there — usually because we ended up a
                // block low). Drop a scaffold into dest.below() so the step
                // completes, instead of falling back and stalling 40 ticks. Skip
                // the cell we currently occupy (can't place a block inside us).
                BlockPos floor = mv.dest.below();
                if (!BlockHelper.canWalkOn(level, floor)
                        && BlockHelper.isReplaceableForPlacement(level, floor)
                        && !floor.equals(entity.blockPosition())) {
                    ItemStack stack = takeScaffold();
                    if (stack != null) {
                        BlockState st = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
                        if (level.setBlock(floor, st, 3)) {
                            stack.shrink(1);
                            entity.getInventory().setChanged();
                            entity.pathTally().addPlaced(st.getBlock());
                            plog("placed ascend landing block " + st.getBlock() + " @ " + floor);
                        }
                    }
                }
            }
        }

        // Arrive only once actually standing at the destination level: require
        // onGround (so we never advance mid-jump or mid-fall) plus a tight height
        // window. The old dy<=0.6-alone test let an ASCEND "arrive" while still
        // half a block short, which advanced into the next step and re-triggered
        // a jump — the stair over-jumping.
        if (horizSqr <= ARRIVE_HORIZ_SQR && entity.onGround() && Math.abs(dyToNode) <= 0.5) {
            advance();
            return Status.RUNNING;
        }

        // Progress-based stuck detection (toward the current node) instead of raw
        // per-tick displacement: being pushed or jittered in place moves the body
        // without getting closer to the node, so displacement was fooled into
        // resetting the counter forever (the "pushed into a pit, stuck forever"
        // bug). Track the best dist² to the node and the dist² at move-start.
        double distSqToNode = horizSqr + dyToNode * dyToNode;
        if (moveStartDistSqToNode < 0.0) {
            moveStartDistSqToNode = distSqToNode;
            bestDistSqToNode = distSqToNode;
        }
        // Shoved off-path (knocked into a pit, pushed by a mob/piston): we're now
        // markedly farther from the node than when this move began → drop the
        // stale path and replan from where we actually are, right away.
        if (distSqToNode > moveStartDistSqToNode + DEVIATION_MARGIN_SQR) {
            plog(String.format("REPLAN deviation: distSq=%.2f > start=%.2f + %.1f (kind=%s)",
                    distSqToNode, moveStartDistSqToNode, DEVIATION_MARGIN_SQR, mv.kind));
            return Status.NEEDS_REPLAN;
        }
        // No progress toward the node for the whole window → stuck, replan.
        if (distSqToNode < bestDistSqToNode - PROGRESS_EPS) {
            bestDistSqToNode = distSqToNode;
            stuckCounter = 0;
        } else if (++stuckCounter >= STUCK_TICKS) {
            plog(String.format("REPLAN stuck: no progress for %dt (kind=%s bestDistSq=%.2f)",
                    STUCK_TICKS, mv.kind, bestDistSqToNode));
            return Status.NEEDS_REPLAN;
        }
        return Status.RUNNING;
    }

    // ---- PILLAR_UP: jump straight up, place the floor block beneath mid-rise ----

    private Status tickPillar(Movement mv) {
        Level level = entity.level();
        double cx = mv.dest.getX() + 0.5;
        double cz = mv.dest.getZ() + 0.5;
        // Drive MoveControl toward the column at the entity's CURRENT height, not
        // the dest (which is above us): a wantedY above makes vanilla MoveControl
        // jump on its own to "reach" it, which fights our explicit jump+place
        // cycle and keeps the body airborne so it can never cleanly ground to
        // start a pillar step. Rising is entirely our explicit jump's job.
        entity.getMoveControl().setWantedPosition(cx, entity.getY(), cz, 0.2);
        entity.getLookControl().setLookAt(cx, mv.dest.getY() + 1.0, cz);

        double cdx = entity.getX() - cx;
        double cdz = entity.getZ() - cz;
        double horizSqr = cdx * cdx + cdz * cdz;
        // Drifted off the column entirely (pushed away) → replan from where we are.
        if (horizSqr > DEVIATION_MARGIN_SQR) {
            plog(String.format("REPLAN pillar deviation: horizSq=%.2f (off the column)", horizSqr));
            return Status.NEEDS_REPLAN;
        }
        boolean centred = horizSqr <= 0.10;   // actually over the column (~0.3 block)

        // Reached the planned top — advance WITHOUT requiring onGround. At the top
        // the body is often at a jump apex (airborne); gating the advance on
        // onGround missed that tick, so the pillar replanned instead of finishing
        // (the "no height gain at topY" bug). feetY is the floored Y, so this only
        // fires once we're genuinely at/above the target level.
        if (entity.blockPosition().getY() >= mv.dest.getY() && centred) {
            advance();
            return Status.RUNNING;
        }

        if (entity.onGround()) {
            if (centred) {
                // Start one pillar cycle from our ACTUAL feet — robust to having
                // fallen/landed a block lower than the plan assumed.
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

        // Vertical-progress stuck: if we stop gaining height (head blocked above
        // the planned top — pillar genuinely infeasible from here), replan. This is
        // the progress-based model (same as MOVE) on the axis a pillar cares about,
        // not a position-displacement check the jump-bounce could fool.
        if (entity.getY() > bestPillarY + 0.05) {
            bestPillarY = entity.getY();
            stuckCounter = 0;
        } else if (++stuckCounter >= STUCK_TICKS) {
            plog(String.format("REPLAN pillar stuck: no height gain for %dt (y=%.2f topY=%d)",
                    STUCK_TICKS, entity.getY(), mv.dest.getY()));
            return Status.NEEDS_REPLAN;
        }
        return Status.RUNNING;
    }

    private void advance() {
        index++;
        phase = Phase.PREPARE_BREAK;
        breakIndex = 0;
        miningStarted = false;
        stuckCounter = 0;
        bestDistSqToNode = Double.MAX_VALUE;
        moveStartDistSqToNode = -1.0;
        pillarPlacedThisCycle = false;
        bestPillarY = Double.NEGATIVE_INFINITY;
        if (index < path.movements.size()) {
            Movement next = path.movements.get(index);
            plog("advance -> idx " + index + "/" + path.movements.size()
                    + " kind=" + next.kind + " dest=" + next.dest);
        } else {
            plog("advance -> idx " + index + "/" + path.movements.size() + " (path end)");
        }
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
