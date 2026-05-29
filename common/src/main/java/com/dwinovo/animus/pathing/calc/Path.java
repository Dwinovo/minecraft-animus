package com.dwinovo.animus.pathing.calc;

import com.dwinovo.animus.pathing.movement.Movement;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * A computed route: an ordered list of {@link Movement} edges from
 * {@link #start} to {@link #end}. {@code partial} marks a best-effort path
 * that approached but did not reach the goal (A* exhausted its node budget
 * or the goal was unreachable) — the executor still walks it to make
 * progress, then a replan continues toward the goal.
 */
public final class Path {

    public final BlockPos start;
    public final BlockPos end;
    public final List<Movement> movements;
    public final boolean partial;

    public Path(BlockPos start, BlockPos end, List<Movement> movements, boolean partial) {
        this.start = start.immutable();
        this.end = end.immutable();
        this.movements = List.copyOf(movements);
        this.partial = partial;
    }

    public boolean isEmpty() {
        return movements.isEmpty();
    }

    public int size() {
        return movements.size();
    }
}
