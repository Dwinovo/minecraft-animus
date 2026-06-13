package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import java.util.Map;

/**
 * Read-only inspector for {@link CheckFurnaceTaskRecord}: report the working
 * state of the furnace at the given position. One-shot — all work is in
 * {@link #onStart}, which sets a terminal state immediately (no pathfinding,
 * no world mutation), so the entity can poll a furnace remotely while busy
 * elsewhere.
 *
 * <p>Designed to generalise: any future "working block" (brewing stand, etc.)
 * can get its own check goal returning the same {@code data} shape.
 */
public final class CheckFurnaceTaskGoal implements CompanionTask {

    private final AnimusPlayer player;
    private final CheckFurnaceTaskRecord r;
    private String message = "";
    private Map<String, Object> data = Map.of();
    private boolean ok = false;

    public CheckFurnaceTaskGoal(AnimusPlayer player, CheckFurnaceTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        AbstractFurnaceBlockEntity furnace = FurnaceEngine.furnaceAt(player.level(), r.pos);
        if (furnace == null) {
            message = "no furnace at " + r.pos.getX() + "," + r.pos.getY() + "," + r.pos.getZ()
                    + " (wrong coords, or the chunk isn't loaded)";
            r.setState(TaskState.FAILED);
            return;
        }
        data = FurnaceEngine.describe(player.level(), r.pos, furnace);
        boolean lit = Boolean.TRUE.equals(data.get("lit"));
        int outputCount = data.get("output_count") instanceof Number n ? n.intValue() : 0;
        int leftToSmelt = data.get("items_left_to_smelt") instanceof Number n ? n.intValue() : 0;
        message = describeStatus(lit, outputCount, leftToSmelt);
        ok = true;
        r.setState(TaskState.SUCCESS);
    }

    private static String describeStatus(boolean lit, int output, int leftToSmelt) {
        if (lit && leftToSmelt > 0) {
            return "smelting (" + leftToSmelt + " left, " + output + " ready to collect)";
        }
        if (leftToSmelt > 0) {
            return "stalled with " + leftToSmelt + " unsmelted — out of fuel? ("
                    + output + " ready to collect)";
        }
        if (output > 0) {
            return "done — " + output + " ready to collect";
        }
        return "idle and empty";
    }

    @Override
    public TaskState tick() {
        return r.getState();   // terminal already; start() did the work
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(message, data);
            case CANCELLED -> TaskResult.cancelled("check interrupted");
            default -> ok ? TaskResult.ok(message, data) : TaskResult.fail(message);
        };
    }
}
