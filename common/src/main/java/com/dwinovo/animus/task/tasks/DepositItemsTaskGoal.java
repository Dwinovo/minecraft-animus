package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.world.Container;

/**
 * Executor for {@link DepositItemsTaskRecord}: walk to a chest/barrel and
 * move items from the entity's inventory into it — the "my bags are full"
 * relief valve and the base-storage workflow.
 */
public final class DepositItemsTaskGoal extends AbstractContainerItemGoal<DepositItemsTaskRecord> {

    public DepositItemsTaskGoal(AnimusEntity entity) {
        super(entity, DepositItemsTaskRecord.TOOL_NAME, DepositItemsTaskRecord.class);
    }

    @Override
    protected void operate(DepositItemsTaskRecord r, Container container) {
        entity.ensureInInventory(r.item);   // pull it back from a hand slot if held
        int have = entity.getInventory().countItem(r.item);
        if (have <= 0) {
            fail("no " + r.label + " in inventory to deposit");
            return;
        }
        int moved = ContainerOps.deposit(entity.getInventory(), container, r.item, r.count);
        if (moved <= 0) {
            fail("the container at " + posLabel() + " is full — take_items something out, "
                    + "or deposit into another container (give its x/y/z)");
            return;
        }
        int remaining = entity.getInventory().countItem(r.item);
        resultData.put("deposited", moved);
        resultData.put("remaining_in_inventory", remaining);
        doneReason = "deposited " + moved + "x " + r.label + " into the container at " + posLabel()
                + (moved < Math.min(r.count, have)
                        ? " — container filled up, " + remaining + " still on me" : "");
        currentRecord.setState(TaskState.SUCCESS);
    }
}
