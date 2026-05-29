package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.Path;
import com.dwinovo.animus.pathing.movement.Movement;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.task.tasks.BlockMiningProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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
 */
public final class PathExecutor {

    public enum Status { RUNNING, ARRIVED, NEEDS_REPLAN, FAILED }

    private enum Phase { PREPARE_BREAK, PREPARE_PLACE, MOVE }

    /** Ticks of near-zero movement during MOVE before we declare a stuck/replan. */
    private static final int STUCK_TICKS = 40;
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

    // Stuck detection during MOVE.
    private Vec3 lastPos;
    private int stuckCounter = 0;

    public PathExecutor(AnimusEntity entity, Path path, double speed) {
        this.entity = entity;
        this.path = path;
        this.speed = speed;
        this.mining = new BlockMiningProgress(entity);
        this.lastPos = entity.position();
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
        return switch (phase) {
            case PREPARE_BREAK -> tickBreak(mv);
            case PREPARE_PLACE -> tickPlace(mv);
            case MOVE -> tickMove(mv);
        };
    }

    // ---- PREPARE: break obstructions one at a time ----

    private Status tickBreak(Movement mv) {
        if (breakIndex >= mv.toBreak.size()) {
            phase = Phase.PREPARE_PLACE;
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
                return Status.NEEDS_REPLAN;
            }
            miningStarted = true;
        }

        switch (mining.tick(target)) {
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
                return Status.NEEDS_REPLAN;
            }
            case FAILED_BLOCK_CHANGED -> {
                mining.cleanup(target);
                miningStarted = false;
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
            return Status.NEEDS_REPLAN;
        }

        ItemStack stack = takeScaffold();
        if (stack == null) {
            // Ran out of scaffolding mid-path — replan; the fresh NavContext
            // will have hasScaffold == false and route around (or fail clean).
            return Status.NEEDS_REPLAN;
        }
        BlockState state = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
        if (!level.setBlock(at, state, 3)) {
            return Status.NEEDS_REPLAN;
        }
        stack.shrink(1);
        entity.getInventory().setChanged();
        phase = Phase.MOVE;
        return Status.RUNNING;
    }

    // ---- MOVE: walk/jump to the destination cell ----

    private Status tickMove(Movement mv) {
        Vec3 center = new Vec3(mv.dest.getX() + 0.5, mv.dest.getY(), mv.dest.getZ() + 0.5);
        entity.getMoveControl().setWantedPosition(center.x, center.y, center.z, speed);
        entity.getLookControl().setLookAt(center.x, center.y + entity.getEyeHeight(), center.z);

        // Help the body up a step / out of a bridge dip.
        if (mv.kind == Movement.Kind.ASCEND) {
            entity.getJumpControl().jump();
        }

        double dx = entity.getX() - center.x;
        double dz = entity.getZ() - center.z;
        double horizSqr = dx * dx + dz * dz;
        double dy = Math.abs(entity.getY() - mv.dest.getY());

        if (horizSqr <= ARRIVE_HORIZ_SQR && dy <= 0.6) {
            advance();
            return Status.RUNNING;
        }

        // Stuck detection.
        if (entity.position().distanceToSqr(lastPos) < 0.0025) {
            if (++stuckCounter >= STUCK_TICKS) {
                return Status.NEEDS_REPLAN;
            }
        } else {
            stuckCounter = 0;
            lastPos = entity.position();
        }
        return Status.RUNNING;
    }

    private void advance() {
        index++;
        phase = Phase.PREPARE_BREAK;
        breakIndex = 0;
        miningStarted = false;
        stuckCounter = 0;
        lastPos = entity.position();
    }

    /** Find and return a scaffolding stack from inventory (not yet shrunk), or null. */
    private ItemStack takeScaffold() {
        SimpleContainer inv = entity.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            Item item = s.getItem();
            if (item instanceof BlockItem && NavContext.SCAFFOLD_ITEMS.contains(item)) {
                return s;
            }
        }
        return null;
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
