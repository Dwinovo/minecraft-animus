package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Instant equip: move one of {@code item} from the entity's inventory into an
 * equipment slot, swapping whatever was there back into the inventory. This is
 * what turns a freshly-{@code craft}ed pickaxe/sword/armor into an actual stat
 * boost — {@link BlockMiningProgress} reads the main-hand item for dig speed,
 * and melee damage picks up the held weapon's modifiers.
 *
 * <h2>Slot routing</h2>
 * If the record names no slot, vanilla {@link net.minecraft.world.entity.LivingEntity#getEquipmentSlotForItem}
 * routes it (armor → its armor slot, shield → off-hand per its component, else
 * main hand). An explicit armor slot is validated with
 * {@code isEquippableInSlot}; hand slots accept anything (you can hold any item).
 *
 * <h2>One-tick lifecycle</h2>
 * All work happens in {@link #onStart}, which sets a terminal state immediately;
 * {@link #onTick} is never reached. No pathfinding, no navigation.
 */
public final class EquipTaskGoal extends LlmTaskGoal<EquipTaskRecord> {

    private String message = "";
    private boolean equipped = false;
    private String slotName = "";

    public EquipTaskGoal(AnimusEntity entity) {
        super(entity, EquipTaskRecord.TOOL_NAME, EquipTaskRecord.class);
    }

    @Override
    protected void onStart(EquipTaskRecord r) {
        SimpleContainer inv = entity.getInventory();

        int invSlot = findItem(inv, r);
        if (invSlot < 0) {
            fail(r, "no " + r.label + " in inventory to equip");
            return;
        }

        ItemStack toEquip = inv.getItem(invSlot).copyWithCount(1);
        EquipmentSlot target = resolveSlot(r, toEquip);
        if (target == null) {
            fail(r, r.label + " can't be equipped in " + r.slot.getName());
            return;
        }

        ItemStack previous = entity.getItemBySlot(target).copy();
        if (!previous.isEmpty() && previous.getItem() == toEquip.getItem()) {
            // Already wearing this exact item there — nothing to do.
            message = r.label + " already equipped in " + target.getName();
            slotName = target.getName();
            equipped = true;
            r.setState(TaskState.SUCCESS);
            return;
        }

        // Consume one from inventory, place the previously-held item back.
        inv.removeItem(invSlot, 1);
        entity.setItemSlot(target, toEquip);
        if (!previous.isEmpty()) {
            ItemStack leftover = inv.addItem(previous);
            if (!leftover.isEmpty()) {
                if (entity.level() instanceof ServerLevel sl) {
                    entity.spawnAtLocation(sl, leftover);
                }
            }
        }
        inv.setChanged();

        slotName = target.getName();
        equipped = true;
        message = previous.isEmpty()
                ? "equipped " + r.label + " in " + slotName
                : "equipped " + r.label + " in " + slotName
                        + " (stowed " + previous.getHoverName().getString() + ")";
        r.setState(TaskState.SUCCESS);
    }

    @Override
    protected void onTick(EquipTaskRecord r) {
        // Never reached — onStart sets a terminal state.
    }

    private int findItem(SimpleContainer inv, EquipTaskRecord r) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.getItem() == r.item) {
                return i;
            }
        }
        return -1;
    }

    /** Resolve the target slot; {@code null} if an explicit armor slot rejects the item. */
    private EquipmentSlot resolveSlot(EquipTaskRecord r, ItemStack stack) {
        if (r.slot == null) {
            return entity.getEquipmentSlotForItem(stack);
        }
        if (r.slot == EquipmentSlot.MAINHAND || r.slot == EquipmentSlot.OFFHAND) {
            return r.slot;   // hands hold anything
        }
        return entity.isEquippableInSlot(stack, r.slot) ? r.slot : null;
    }

    private void fail(EquipTaskRecord r, String reason) {
        message = reason;
        r.setState(TaskState.FAILED);
    }

    @Override
    protected TaskResult buildResult(EquipTaskRecord r, TaskState finalState) {
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
