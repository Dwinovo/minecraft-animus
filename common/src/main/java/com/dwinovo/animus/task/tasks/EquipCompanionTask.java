package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code equip_item} on the player body: move one of {@code item} from the
 * player inventory into an equipment slot, stowing whatever was there. One-tick
 * (all work in {@link #start()}); the player-body twin of {@code EquipTaskGoal}.
 */
public final class EquipCompanionTask implements CompanionTask {

    private final AnimusPlayer player;
    private final EquipTaskRecord r;
    private String message = "";
    private boolean equipped = false;
    private String slotName = "";

    public EquipCompanionTask(AnimusPlayer player, EquipTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        Inventory inv = player.getInventory();
        int invSlot = findItem(inv);
        if (invSlot < 0) {
            fail("no " + r.label + " in inventory to equip");
            return;
        }
        ItemStack toEquip = inv.getItem(invSlot).copyWithCount(1);
        EquipmentSlot target = resolveSlot(toEquip);
        if (target == null) {
            fail(r.label + " can't be equipped in " + r.slot.getName());
            return;
        }
        ItemStack previous = player.getItemBySlot(target).copy();
        if (!previous.isEmpty() && previous.getItem() == toEquip.getItem()) {
            message = r.label + " already equipped in " + target.getName();
            slotName = target.getName();
            equipped = true;
            r.setState(TaskState.SUCCESS);
            return;
        }
        inv.removeItem(invSlot, 1);
        player.setItemSlot(target, toEquip);
        if (!previous.isEmpty()) {
            inv.add(previous);                       // mutates `previous` down by what fit
            if (!previous.isEmpty() && player.level() instanceof ServerLevel sl) {
                player.spawnAtLocation(sl, previous);   // overflow → drop
            }
        }
        inv.setChanged();
        slotName = target.getName();
        equipped = true;
        message = previous.isEmpty()
                ? "equipped " + r.label + " in " + slotName
                : "equipped " + r.label + " in " + slotName;
        r.setState(TaskState.SUCCESS);
    }

    @Override
    public TaskState tick() {
        return r.getState();   // terminal already; start() did the work
    }

    private int findItem(Inventory inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.getItem() == r.item) return i;
        }
        return -1;
    }

    private EquipmentSlot resolveSlot(ItemStack stack) {
        if (r.slot == null) return player.getEquipmentSlotForItem(stack);
        if (r.slot == EquipmentSlot.MAINHAND || r.slot == EquipmentSlot.OFFHAND) return r.slot;
        return player.isEquippableInSlot(stack, r.slot) ? r.slot : null;
    }

    private void fail(String reason) {
        message = reason;
        r.setState(TaskState.FAILED);
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        if (equipped) data.put("slot", slotName);
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(message, data);
            case CANCELLED -> TaskResult.cancelled("equip interrupted");
            default -> TaskResult.fail(message.isEmpty() ? "equip failed" : message, data);
        };
    }
}
