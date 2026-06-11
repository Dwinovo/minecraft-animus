package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for {@link DropItemsTaskRecord}: toss items forward onto the
 * ground. Completes in a single tick. The toss gets a 40-tick pickup delay
 * (vanilla player-drop semantics) and a forward arc so the entity doesn't
 * immediately vacuum its own discard back up.
 */
public final class DropItemsTaskGoal extends LlmTaskGoal<DropItemsTaskRecord> {

    private int dropped;
    private String doneReason = "done";

    public DropItemsTaskGoal(AnimusEntity entity) {
        super(entity, DropItemsTaskRecord.TOOL_NAME, DropItemsTaskRecord.class);
    }

    @Override
    protected void onStart(DropItemsTaskRecord r) {
        this.dropped = 0;
        if (!(entity.level() instanceof ServerLevel sl)) {
            doneReason = "not on a server level";
            r.setState(TaskState.FAILED);
            return;
        }
        entity.ensureInInventory(r.item);   // pull it back from a hand slot if held
        SimpleContainer inv = entity.getInventory();
        int have = inv.countItem(r.item);
        if (have <= 0) {
            doneReason = "no " + r.label + " in inventory to drop";
            r.setState(TaskState.FAILED);
            return;
        }
        dropped = Math.min(r.count, have);
        inv.removeItemType(r.item, dropped);
        inv.setChanged();

        // Toss in stack-sized lumps with a forward arc + pickup delay.
        Vec3 look = entity.getLookAngle();
        int remaining = dropped;
        while (remaining > 0) {
            int lump = Math.min(remaining, new ItemStack(r.item).getMaxStackSize());
            remaining -= lump;
            ItemEntity drop = new ItemEntity(sl,
                    entity.getX(), entity.getEyeY() - 0.3, entity.getZ(),
                    new ItemStack(r.item, lump),
                    look.x * 0.3, 0.1, look.z * 0.3);
            drop.setPickUpDelay(40);
            sl.addFreshEntity(drop);
        }
        doneReason = "dropped " + dropped + "x " + r.label
                + (dropped < r.count ? " (only had " + dropped + ")" : "");
        r.setState(TaskState.SUCCESS);
    }

    @Override
    protected void onTick(DropItemsTaskRecord r) {
        // Single-tick action; onStart already settled the state.
    }

    @Override
    protected TaskResult buildResult(DropItemsTaskRecord r, TaskState finalState) {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("dropped", dropped);
        data.put("remaining_in_inventory", entity.getInventory().countItem(r.item));
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case CANCELLED -> TaskResult.cancelled("drop interrupted");
            case TIMEOUT -> TaskResult.timeout("drop timed out unexpectedly");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
