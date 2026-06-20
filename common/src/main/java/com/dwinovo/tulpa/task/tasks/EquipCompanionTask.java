package com.dwinovo.tulpa.task.tasks;

import com.dwinovo.tulpa.entity.TulpaPlayer;
import com.dwinovo.tulpa.task.CompanionTask;
import com.dwinovo.tulpa.task.PlayerInv;
import com.dwinovo.tulpa.task.TaskResult;
import com.dwinovo.tulpa.task.TaskState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code equip_item} on the player body. Equips the way a real player does — by holding the item and
 * RIGHT-CLICKING it: the item's own use behaviour does the equip, with the proper sound + events and
 * routing to the correct slot. That one native path covers vanilla armor (the {@code Equippable}
 * component), shields, AND modded accessories like Curios / Trinkets (their right-click handler slots
 * the item into a curio slot) — no per-mod integration. Explicit main/off-hand requests are a direct
 * item-conserving placement instead; a vanilla-slot direct set is the fallback if the right-click
 * didn't take. One-tick (all work in {@link #start()}).
 */
public final class EquipCompanionTask implements CompanionTask {

    private final TulpaPlayer player;
    private final EquipTaskRecord r;
    private String message = "";
    private boolean equipped = false;
    private String slotName = "";

    public EquipCompanionTask(TulpaPlayer player, EquipTaskRecord record) {
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

        // Explicit hand placement: just select / set it, no right-click (we don't want to "use" a
        // shield or tool, only hold it).
        if (r.slot == EquipmentSlot.MAINHAND) {
            player.holdInHand(invSlot);
            succeed("holding " + r.label + " in main hand", "mainhand");
            return;
        }
        if (r.slot == EquipmentSlot.OFFHAND) {
            directSet(EquipmentSlot.OFFHAND, invSlot, inv.getItem(invSlot).copyWithCount(1));
            return;
        }

        // Default: equip via a native right-click. The held item's use behaviour equips it — vanilla
        // armor (Equippable), or a Curios/Trinkets accessory (its mod's right-click handler) — to the
        // right slot, with sound + events. Confirmed by the item leaving the inventory.
        Item want = inv.getItem(invSlot).getItem();
        int before = PlayerInv.count(inv, want);
        player.holdInHand(invSlot);
        player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (PlayerInv.count(inv, want) < before) {
            succeed("equipped " + r.label + slotSuffix(want), foundVanillaSlot(want));
            return;
        }

        // Right-click didn't equip it (e.g. an item with no equip-on-use behaviour). Fall back to a
        // direct vanilla-slot set. The item is currently held in the selected hotbar slot.
        ItemStack one = inv.getItem(invSlot).copyWithCount(1);
        EquipmentSlot target = resolveSlot(one);
        if (target == EquipmentSlot.MAINHAND) {
            succeed("holding " + r.label + " in main hand", "mainhand");   // already in hand
            return;
        }
        if (target != null) {
            directSet(target, inv.selected, one);
            return;
        }
        fail(r.label + " can't be equipped" + (r.slot != null ? " in " + r.slot.getName() : ""));
    }

    @Override
    public TaskState tick() {
        return r.getState();   // terminal already; start() did the work
    }

    /** Direct, item-conserving set of one item into {@code slot}, stowing whatever was there (and
     *  dropping it only if the inventory is full). Used for off-hand and as the right-click fallback. */
    private void directSet(EquipmentSlot slot, int fromSlot, ItemStack one) {
        Inventory inv = player.getInventory();
        ItemStack previous = player.getItemBySlot(slot).copy();
        if (!previous.isEmpty() && previous.getItem() == one.getItem()) {
            succeed(r.label + " already equipped in " + slot.getName(), slot.getName());
            return;
        }
        inv.removeItem(fromSlot, 1);
        player.setItemSlot(slot, one);
        if (!previous.isEmpty()) {
            inv.add(previous);                              // mutates `previous` down by what fit
            if (!previous.isEmpty() && player.level() instanceof ServerLevel sl) {
                player.spawnAtLocation(previous);       // overflow → drop
            }
        }
        inv.setChanged();
        succeed("equipped " + r.label + " in " + slot.getName(), slot.getName());
    }

    private int findItem(Inventory inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.getItem() == r.item) return i;
        }
        return -1;
    }

    /** The vanilla equipment slot this item routes to, honouring an explicit non-hand request. */
    private EquipmentSlot resolveSlot(ItemStack stack) {
        if (r.slot == null) return player.getEquipmentSlotForItem(stack);
        if (r.slot == EquipmentSlot.MAINHAND || r.slot == EquipmentSlot.OFFHAND) return r.slot;
        return player.getEquipmentSlotForItem(stack) == r.slot ? r.slot : null;
    }

    /** After a right-click equip, find which vanilla armor/off-hand slot now holds the item (null = a
     *  modded accessory slot we can't name). */
    private String foundVanillaSlot(Item want) {
        for (EquipmentSlot s : EquipmentSlot.values()) {
            if (s == EquipmentSlot.MAINHAND) continue;
            if (player.getItemBySlot(s).getItem() == want) return s.getName();
        }
        return null;
    }

    private String slotSuffix(Item want) {
        String s = foundVanillaSlot(want);
        return s != null ? " in " + s : " (accessory slot)";
    }

    private void succeed(String msg, String slot) {
        message = msg;
        slotName = slot == null ? "" : slot;
        equipped = true;
        r.setState(TaskState.SUCCESS);
    }

    private void fail(String reason) {
        message = reason;
        r.setState(TaskState.FAILED);
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        if (equipped && !slotName.isEmpty()) data.put("slot", slotName);
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(message, data);
            case CANCELLED -> TaskResult.cancelled("equip interrupted");
            default -> TaskResult.fail(message.isEmpty() ? "equip failed" : message, data);
        };
    }
}
