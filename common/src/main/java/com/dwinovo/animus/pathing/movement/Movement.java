package com.dwinovo.animus.pathing.movement;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * One edge in the pathfinding graph: a single step from a feet-position to an
 * adjacent feet-position, possibly requiring some blocks to be broken and/or
 * one scaffolding block to be placed. Mirrors a Baritone {@code Movement} /
 * mineflayer {@code Move} node.
 *
 * <p>Immutable value object produced by {@link Moves} during A* expansion and
 * consumed by the {@code PathExecutor} at runtime. The {@link #cost} already
 * folds in walk-time + mining-time + placement-penalty (or
 * {@link com.dwinovo.animus.pathing.util.ActionCosts#COST_INF} if infeasible —
 * such movements are never emitted, {@link Moves} returns {@code null}).
 */
public final class Movement {

    /** Kind tag, used by the executor to pick an animation / phase order. */
    public enum Kind { TRAVERSE, ASCEND, DESCEND, FALL, DIAGONAL, PILLAR, DIG_DOWN }

    public final Kind kind;
    /** Feet position the entity ends this step at. */
    public final BlockPos dest;
    /** Total step cost in ticks. */
    public final double cost;
    /** Blocks to break before/while moving (head & feet obstructions). May be empty. */
    public final List<BlockPos> toBreak;
    /** Scaffolding block to place (floor under {@link #dest}), or {@code null}. */
    public final BlockPos toPlace;

    public Movement(Kind kind, BlockPos dest, double cost,
                    List<BlockPos> toBreak, BlockPos toPlace) {
        this.kind = kind;
        this.dest = dest.immutable();
        this.cost = cost;
        this.toBreak = List.copyOf(toBreak);
        this.toPlace = toPlace == null ? null : toPlace.immutable();
    }

    public boolean requiresPlace() {
        return toPlace != null;
    }

    public boolean requiresBreak() {
        return !toBreak.isEmpty();
    }

    @Override
    public String toString() {
        return kind + "->" + dest.toShortString()
                + " (cost=" + String.format("%.1f", cost)
                + (requiresBreak() ? ", break=" + toBreak.size() : "")
                + (requiresPlace() ? ", place" : "") + ")";
    }
}
