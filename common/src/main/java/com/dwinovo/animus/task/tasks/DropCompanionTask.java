package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.PlayerInv;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** {@code drop_items} on the player body — toss items forward, natively. One-tick. */
public final class DropCompanionTask implements CompanionTask {

    private final AnimusPlayer player;
    private final DropItemsTaskRecord r;
    private int dropped;
    private String doneReason = "done";

    public DropCompanionTask(AnimusPlayer player, DropItemsTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        dropped = 0;
        if (!(player.level() instanceof ServerLevel)) {
            doneReason = "not on a server level";
            r.setState(TaskState.FAILED);
            return;
        }
        Inventory inv = player.getInventory();
        int have = PlayerInv.count(inv, r.item);
        if (have <= 0) {
            doneReason = "no " + r.label + " in inventory to drop";
            r.setState(TaskState.FAILED);
            return;
        }
        dropped = Math.min(r.count, have);
        PlayerInv.remove(inv, r.item, dropped);

        // Toss like a real player: native Player.drop(stack, false) throws each stack in the facing
        // direction with vanilla motion + pickup delay and fires the drop event (mods watching item
        // tosses see it) — instead of hand-building an ItemEntity with a made-up velocity.
        int max = new ItemStack(r.item).getMaxStackSize();
        int remaining = dropped;
        while (remaining > 0) {
            int lump = Math.min(remaining, max);
            remaining -= lump;
            player.drop(new ItemStack(r.item, lump), false);
        }
        doneReason = "dropped " + dropped + "x " + r.label
                + (dropped < r.count ? " (only had " + dropped + ")" : "");
        r.setState(TaskState.SUCCESS);
    }

    @Override
    public TaskState tick() {
        return r.getState();
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("dropped", dropped);
        data.put("remaining_in_inventory", PlayerInv.count(player.getInventory(), r.item));
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case CANCELLED -> TaskResult.cancelled("drop interrupted");
            case TIMEOUT -> TaskResult.timeout("drop timed out unexpectedly");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
