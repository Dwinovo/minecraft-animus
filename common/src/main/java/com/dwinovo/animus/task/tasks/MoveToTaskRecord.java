package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;

/**
 * Typed task descriptor for the {@code move_to} tool. Mirrors Baritone's
 * {@code goto}, whose goal type is chosen by WHICH coordinates are supplied
 * (arg arity): the LLM picks its intent by filling only the fields it means.
 * <ul>
 *   <li>{@code x} + {@code z} (no {@code y}) → {@link Kind#COLUMN} (Baritone
 *       {@code GoalXZ}): walk to that location, Y auto-resolved to the surface.
 *       The default "go there" — a guessed Y can never make it unreachable.</li>
 *   <li>{@code x} + {@code y} + {@code z} → {@link Kind#BLOCK} (Baritone
 *       {@code GoalBlock}): one exact cell (a verified-reachable spot).</li>
 *   <li>{@code y} only → {@link Kind#YLEVEL} (Baritone {@code GoalYLevel}):
 *       change elevation to that height.</li>
 * </ul>
 * Coordinates are nullable ({@code null} = "not supplied"); the deadline-based
 * timeout is handled by the base class.
 */
public final class MoveToTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "move_to";

    public enum Kind { BLOCK, COLUMN, YLEVEL }

    /** Nullable: {@code null} means the LLM did not supply this axis. */
    public final Double x;
    public final Double y;
    public final Double z;
    /** PathNavigation speed multiplier; 1.0 ≈ entity's MOVEMENT_SPEED attribute. */
    public final double speed;
    public final Kind kind;

    public MoveToTaskRecord(String toolCallId, long deadlineGameTime,
                            Double x, Double y, Double z, double speed) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.x = x;
        this.y = y;
        this.z = z;
        this.speed = speed;
        this.kind = resolveKind(x, y, z);
    }

    /**
     * Map supplied-axes → goal kind (Baritone {@code RelativeGoal} arity rules,
     * adapted to named nullable fields). Throws a teaching error for ambiguous
     * combos so the LLM learns the valid shapes.
     */
    private static Kind resolveKind(Double x, Double y, Double z) {
        boolean hasX = x != null, hasY = y != null, hasZ = z != null;
        if (hasX && hasZ) {
            return hasY ? Kind.BLOCK : Kind.COLUMN;
        }
        if (hasY && !hasX && !hasZ) {
            return Kind.YLEVEL;
        }
        throw new IllegalArgumentException(
                "move_to needs either x+z (a location; omit y to auto-resolve the "
                + "surface), x+y+z (one exact cell), or y alone (a target height). "
                + "Got " + (hasX ? "x" : "") + (hasY ? "y" : "") + (hasZ ? "z" : ""));
    }

    @Override
    public String describe() {
        return switch (kind) {
            case BLOCK -> TOOL_NAME + " " + (int) (double) x + "," + (int) (double) y + "," + (int) (double) z;
            case COLUMN -> TOOL_NAME + " x=" + (int) (double) x + " z=" + (int) (double) z;
            case YLEVEL -> TOOL_NAME + " y=" + (int) (double) y;
        };
    }
}
