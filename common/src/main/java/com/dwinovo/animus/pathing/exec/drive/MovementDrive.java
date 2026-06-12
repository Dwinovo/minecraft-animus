package com.dwinovo.animus.pathing.exec.drive;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.exec.BodyMotor;
import com.dwinovo.animus.pathing.movement.Movement;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.task.tasks.BlockMiningProgress;
import com.dwinovo.animus.task.tasks.FakePlayerUse;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * One movement's EXECUTION, owned by the movement kind instead of a
 * kind-switch in the executor (the Baritone shape: {@code Movement.update()}
 * drives itself; the executor only relocalizes, meters and advances).
 *
 * <p>The shared prepare pipeline lives here in the base: every drive first
 * clears its movement's {@code toBreak} obstructions one at a time, then
 * places the {@code toPlace} floor block if it declares one, then hands
 * control to the subclass {@link #drive()}. Subclasses own their kind's
 * locomotion and sub-state entirely — pillar windows, parkour ballistics,
 * descent braking no longer leak into the executor as fields.
 *
 * <p>A drive instance lives exactly as long as its movement is current;
 * resync/advance discards it (after {@link #stop()}), so sub-state never
 * survives a movement change — the bug class the old shared-fields executor
 * had to reset by hand in {@code resetMoveState()}.
 */
public abstract class MovementDrive {

    /** Drive verdict per tick. */
    public enum Result {
        RUNNING,
        /** Movement completed — executor should advance to the next. */
        STEP_DONE,
        /** World/state no longer matches the plan — executor should replan. */
        NEEDS_REPLAN
    }

    protected final AnimusEntity entity;
    protected final Movement mv;
    protected final BodyMotor motor;
    protected final DriveHost host;

    private final BlockMiningProgress mining;
    private int breakIndex = 0;
    private boolean miningStarted = false;
    private boolean floorPlaced = false;

    protected MovementDrive(AnimusEntity entity, Movement mv, DriveHost host) {
        this.entity = entity;
        this.mv = mv;
        this.host = host;
        this.motor = entity.motor();
        this.mining = new BlockMiningProgress(entity);
    }

    /** The movement this drive executes. */
    public final Movement movement() {
        return mv;
    }

    /** Advance one tick: prepare (break → place), then the kind's locomotion. */
    public final Result tick() {
        if (breakIndex < mv.toBreak.size()) {
            return tickBreak();
        }
        if (placesFloor() && !floorPlaced) {
            return tickPlace();
        }
        return drive();
    }

    /** The kind-specific locomotion, entered once preparation is complete. */
    protected abstract Result drive();

    /** PILLAR/PARKOUR place mid-move (or not at all) — they opt out here. */
    protected boolean placesFloor() {
        return true;
    }

    /** Teardown on resync/advance/cancel: clear the crack overlay etc. */
    public void stop() {
        if (miningStarted && breakIndex < mv.toBreak.size()) {
            mining.cleanup(mv.toBreak.get(breakIndex));
        }
    }

    // ---- shared prepare: break obstructions one at a time ----

    private Result tickBreak() {
        BlockPos target = mv.toBreak.get(breakIndex);
        Level level = entity.level();

        // Block may already be gone (someone/something cleared it) — skip.
        if (BlockHelper.canWalkThrough(level, target)) {
            mining.cleanup(target);
            miningStarted = false;
            breakIndex++;
            return Result.RUNNING;
        }

        if (!miningStarted) {
            if (!mining.tryStart(target)) {
                host.log("REPLAN: obstruction " + target + " became unmineable");
                return Result.NEEDS_REPLAN;
            }
            // World changed under the plan (stone → obsidian, tool swapped):
            // if just THIS dig would blow the movement's stall budget, replan
            // now and let the search re-cost honestly instead of grinding
            // toward a guaranteed stall.
            if (mining.plannedTicks() > (int) (mv.cost * 4) + 60) {
                mining.cleanup(target);
                host.log("REPLAN: obstruction " + target + " now far more expensive than planned ("
                        + mining.plannedTicks() + "t vs cost " + (int) mv.cost + ")");
                return Result.NEEDS_REPLAN;
            }
            miningStarted = true;
        }

        BlockMiningProgress.Outcome outcome = mining.tick(target);
        if (outcome == BlockMiningProgress.Outcome.COMPLETED && mining.startState() != null) {
            // Count the obstruction we dug to clear the route (we broke it — a
            // BLOCK_GONE means someone else did, so it isn't tallied).
            entity.pathTally().addDug(mining.startState().getBlock());
            host.log("dug obstruction " + mining.startState().getBlock() + " @ " + target);
        }
        switch (outcome) {
            case IN_PROGRESS -> { /* keep swinging */ }
            case COMPLETED, FAILED_BLOCK_GONE -> {
                mining.cleanup(target);
                miningStarted = false;
                breakIndex++;
            }
            case FAILED_OUT_OF_REACH -> {
                mining.cleanup(target);
                miningStarted = false;
                host.log("REPLAN: obstruction " + target + " out of reach mid-dig");
                return Result.NEEDS_REPLAN;
            }
            case FAILED_BLOCK_CHANGED -> {
                mining.cleanup(target);
                miningStarted = false;
                host.log("REPLAN: obstruction " + target + " changed mid-dig");
                return Result.NEEDS_REPLAN;
            }
        }
        return Result.RUNNING;
    }

    // ---- shared prepare: place the scaffolding floor block, if any ----

    private Result tickPlace() {
        if (mv.toPlace == null) {
            floorPlaced = true;
            return Result.RUNNING;
        }
        Level level = entity.level();
        BlockPos at = mv.toPlace;

        if (!BlockHelper.isReplaceableForPlacement(level, at)) {
            // Already filled (or no longer placeable) — if it's now solid we
            // can just walk; otherwise replan.
            if (BlockHelper.canWalkOn(level, at)) {
                floorPlaced = true;
                return Result.RUNNING;
            }
            host.log("REPLAN: scaffold spot " + at + " not placeable and not walkable");
            return Result.NEEDS_REPLAN;
        }
        switch (placeScaffold(at)) {
            case PLACED -> floorPlaced = true;
            case NO_SUPPORT -> {
                host.log("REPLAN: no support face left to place scaffold against @ " + at);
                return Result.NEEDS_REPLAN;
            }
            case REFUSED -> {
                // Vanilla turned the click down (a body overlaps the cell, a
                // protection rule, …) — re-centre on the source and retry.
                Vec3 src = Vec3.atBottomCenterOf(mv.src);
                motor.steer(BodyMotor.Owner.PATH, src.x, src.y, src.z, host.userSpeed());
            }
            case OUT_OF_BLOCKS -> {
                // Ran out of scaffolding mid-path — replan; the fresh
                // NavContext will have hasScaffold == false and route around.
                host.log("REPLAN: out of scaffolding for " + at);
                return Result.NEEDS_REPLAN;
            }
        }
        return Result.RUNNING;
    }

    // ---- shared helpers ----

    /** Outcome of {@link #placeScaffold}: the fake-player result + an empty-handed case. */
    protected enum ScaffoldOutcome { PLACED, NO_SUPPORT, REFUSED, OUT_OF_BLOCKS }

    /**
     * Place one scaffold block into {@code cell} through the fake player —
     * vanilla placement rules (entity-collision rejection included), tally
     * and ledger bookkeeping on success.
     */
    protected final ScaffoldOutcome placeScaffold(BlockPos cell) {
        int slot = scaffoldSlot();
        if (slot < 0) return ScaffoldOutcome.OUT_OF_BLOCKS;
        var block = ((BlockItem) entity.getInventory().getItem(slot).getItem()).getBlock();
        return switch (FakePlayerUse.placeScaffold(entity, slot, cell)) {
            case PLACED -> {
                entity.pathTally().addPlaced(block);
                // Ledger the placement: auto_mine must never loot our own bridge.
                entity.scaffoldLedger().record(cell, block);
                host.log("placed scaffold " + block + " @ " + cell);
                yield ScaffoldOutcome.PLACED;
            }
            case NO_SUPPORT -> ScaffoldOutcome.NO_SUPPORT;
            case REFUSED -> ScaffoldOutcome.REFUSED;
        };
    }

    /** Inventory slot of the first scaffolding stack, or -1. */
    private int scaffoldSlot() {
        var inv = entity.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (NavContext.isScaffold(inv.getItem(i))) return i;
        }
        return -1;
    }
}
