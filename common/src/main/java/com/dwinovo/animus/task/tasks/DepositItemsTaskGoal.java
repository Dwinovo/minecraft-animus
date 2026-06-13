package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.world.Container;

/**
 * Executor for {@link DepositItemsTaskRecord}: walk to a chest/barrel and
 * move items from the entity's inventory into it — the "my bags are full"
 * relief valve and the base-storage workflow.
 */
public final class DepositItemsTaskGoal extends AbstractContainerItemGoal<DepositItemsTaskRecord> {

    public DepositItemsTaskGoal(AnimusPlayer player, DepositItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void operate(DepositItemsTaskRecord r, Container container) {
        var inv = player.getInventory();
        int have = ContainerOps.countIn(inv, r.item);
        if (have <= 0) {
            fail("no " + r.label + " in inventory to deposit");
            return;
        }
        int moved = ContainerOps.deposit(inv, container, r.item, r.count);
        if (moved <= 0) {
            fail("the container at " + posLabel() + " is full — take_items something out, "
                    + "or deposit into another container (give its x/y/z)");
            return;
        }
        int remaining = ContainerOps.countIn(inv, r.item);
        resultData.put("deposited", moved);
        resultData.put("remaining_in_inventory", remaining);
        doneReason = "deposited " + moved + "x " + r.label + " into the container at " + posLabel()
                + (moved < Math.min(r.count, have)
                        ? " — container filled up, " + remaining + " still on me" : "");
        r.setState(TaskState.SUCCESS);
    }
}
