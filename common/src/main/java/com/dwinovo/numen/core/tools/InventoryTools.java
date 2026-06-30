package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.agent.tool.api.Arg;
import com.dwinovo.numen.agent.tool.api.NumenAction;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.core.task.CollectItemsTaskRecord;
import com.dwinovo.numen.core.task.DropItemsTaskRecord;
import com.dwinovo.numen.core.task.EatItemTaskRecord;
import com.dwinovo.numen.core.task.EquipTaskRecord;
import com.dwinovo.numen.core.task.WaitTaskRecord;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Inventory-management tools authored on the {@link NumenAction} surface —
 * equip / eat / drop / collect / wait, migrated from the hand-written
 * {@code NumenTool} classes they replace. Each returns a {@link TaskRecord},
 * so the adapter ships it to the body and the task queue runs it; the
 * {@link ToolContext} carries the call id and deadline basis. Behaviour matches
 * the originals.
 */
public final class InventoryTools {

    private static final long EQUIP_TIMEOUT_TICKS = 5 * 20;   // instant; generous floor

    /** Generous — covers any food's eat duration (most ~1.6s) plus buffer. */
    private static final long EAT_TIMEOUT_TICKS = 15 * 20;

    private static final int DROP_MAX_COUNT = 999;
    private static final long DROP_TIMEOUT_TICKS = 10 * 20;

    private static final int COLLECT_DEFAULT_RADIUS = 16;
    private static final int COLLECT_MAX_RADIUS = 48;
    private static final long COLLECT_TIMEOUT_TICKS = 60 * 20;   // 1 min

    /** Cap one wait at 5 minutes; longer vigils chain calls (each is a checkpoint). */
    private static final int WAIT_MAX_SECONDS = 300;
    /** Headroom past the wait itself so the deadline never races the wake-up. */
    private static final long WAIT_DEADLINE_MARGIN_TICKS = 100;

    @NumenAction(name = "equip_item", timeoutTicks = EQUIP_TIMEOUT_TICKS, description =
            "Equip an item from your OWN inventory so it actually takes effect — "
            + "a tool/weapon to the main hand (speeds up auto_mine, boosts melee), "
            + "armor to its slot, a modded accessory (Curios / Trinkets ring, "
            + "amulet, …) to its accessory slot. It equips by right-clicking the "
            + "item the way a player does, so armor and accessories route to the "
            + "correct slot on their own. Omit slot for that auto-routing; set it "
            + "only to force a hand or a specific vanilla armor piece. Whatever was "
            + "equipped before is stowed back. Fails if the item isn't in your "
            + "inventory.")
    public TaskRecord equipItem(
            @Arg("Namespaced id of the item to equip; must be in the inventory.") String item_id,
            @Arg(value = "Optional target slot; omit to auto-route by item type.",
                    required = false,
                    enumValues = {"mainhand", "offhand", "head", "chest", "legs", "feet"}) String slot,
            ToolContext ctx) {
        Item item = ToolArgs.parseItem(item_id);
        EquipmentSlot equipSlot = readSlot(slot);

        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new EquipTaskRecord(ctx.toolCallId(), ctx.deadline(EQUIP_TIMEOUT_TICKS), item, equipSlot, label);
    }

    /** Parse the optional slot; {@code null} means auto-route. */
    private static EquipmentSlot readSlot(String slot) {
        if (slot == null) {
            return null;
        }
        String name = slot.toLowerCase();
        return switch (name) {
            case "mainhand", "hand" -> EquipmentSlot.MAINHAND;
            case "offhand" -> EquipmentSlot.OFFHAND;
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            default -> throw new IllegalArgumentException("unknown slot: " + name);
        };
    }

    @NumenAction(name = "eat_item", timeoutTicks = EAT_TIMEOUT_TICKS, description =
            "Eat or drink a consumable from your inventory. It's a real timed "
            + "action — chewing animation, particles and sound play over the eat "
            + "duration, and only when it finishes does it restore your hunger + "
            + "saturation and apply the item's effects (e.g. a golden apple's "
            + "regeneration/absorption). Your HP then regenerates naturally from "
            + "saturation, the same as a real player — so eat to refill hunger and "
            + "let health recover. Fails if you don't carry it, it isn't a "
            + "consumable, or your hunger is already full (the food is kept).")
    public TaskRecord eatItem(
            @Arg("Namespaced id of the food to eat, e.g. minecraft:cooked_beef.") String item_id,
            ToolContext ctx) {
        ResourceLocation id = ResourceLocation.tryParse(item_id);
        if (id == null) {
            throw new IllegalArgumentException("item_id is not a valid id: " + item_id);
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            throw new IllegalArgumentException("unknown item: " + id);
        }
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new EatItemTaskRecord(ctx.toolCallId(), ctx.deadline(EAT_TIMEOUT_TICKS), item, label);
    }

    @NumenAction(name = "drop_items", timeoutTicks = DROP_TIMEOUT_TICKS, description =
            "Drop items from your inventory onto the ground in front of you — "
            + "to hand something to your owner, or to shed junk when your "
            + "inventory is full and no chest is nearby (prefer deposit_items "
            + "when one is — dropped items despawn after 5 minutes). count "
            + "above what you carry drops everything you have of it. Returns "
            + "how many were dropped and how many remain.")
    public TaskRecord dropItems(
            @Arg("Namespaced id of the item to drop, e.g. minecraft:cobblestone.") String item_id,
            @Arg(value = "How many to drop (1-" + DROP_MAX_COUNT + ").",
                    min = 1, max = DROP_MAX_COUNT) int count,
            ToolContext ctx) {
        Item item = ToolArgs.parseItem(item_id);
        count = Math.clamp(count, 1, DROP_MAX_COUNT);
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new DropItemsTaskRecord(ctx.toolCallId(), ctx.deadline(DROP_TIMEOUT_TICKS),
                item, count, label);
    }

    @NumenAction(name = "collect_items", timeoutTicks = COLLECT_TIMEOUT_TICKS, description =
            "Pick up dropped items off the ground nearby. The entity travels to "
            + "each dropped item (it auto-absorbs items it gets close to) until "
            + "none remain in range — terrain is handled automatically: it digs "
            + "and bridges on its own if drops landed in a pit or across a gap. "
            + "Optionally restrict to specific item_ids "
            + "(omit to collect everything). Optional radius (default "
            + COLLECT_DEFAULT_RADIUS + "). Use after auto_mine or hunt to gather "
            + "scattered drops. Returns how many drops were collected.")
    public TaskRecord collectItems(
            @Arg(value = "Optional namespaced item id(s) to collect; omit to collect all.",
                    required = false) List<String> item_ids,
            @Arg(value = "Optional search radius in blocks (default " + COLLECT_DEFAULT_RADIUS + ").",
                    min = 1, max = COLLECT_MAX_RADIUS, required = false) Integer radius,
            ToolContext ctx) {
        // Lenient set from the id list: unparseable / unknown ids are skipped, and
        // an absent list yields an empty set — the "match everything" filter.
        Set<Item> filter = new LinkedHashSet<>();
        if (item_ids != null) {
            for (String el : item_ids) {
                if (el == null) continue;
                ResourceLocation id = ResourceLocation.tryParse(el);
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    filter.add(BuiltInRegistries.ITEM.get(id));
                }
            }
        }

        int searchRadius = COLLECT_DEFAULT_RADIUS;
        if (radius != null) {
            searchRadius = radius;
            if (searchRadius < 1) searchRadius = 1;
            if (searchRadius > COLLECT_MAX_RADIUS) searchRadius = COLLECT_MAX_RADIUS;
        }

        String label = filter.isEmpty() ? "all items" : labelFor(filter);
        return new CollectItemsTaskRecord(ctx.toolCallId(), ctx.deadline(COLLECT_TIMEOUT_TICKS),
                filter, searchRadius, label);
    }

    private static String labelFor(Set<Item> filter) {
        Item first = filter.iterator().next();
        String path = BuiltInRegistries.ITEM.getKey(first).getPath();
        return filter.size() == 1 ? path : path + "+" + (filter.size() - 1);
    }

    @NumenAction(name = "wait", timeoutTicks = WAIT_MAX_SECONDS * 20L + WAIT_DEADLINE_MARGIN_TICKS, description =
            "Wait in place for the given number of seconds, doing nothing on "
            + "purpose. Use it when the next step depends on time passing: a "
            + "furnace batch (~10s per item), nightfall/daybreak, an owner who "
            + "said \"wait here\". Max " + WAIT_MAX_SECONDS + "s per call — for "
            + "longer vigils, call it again after re-checking the situation "
            + "(each return is a natural checkpoint). The optional reason is "
            + "shown to the owner on the debug overlay. Interruptible by the "
            + "owner at any time.")
    public TaskRecord wait(
            @Arg(value = "How long to wait, in real seconds (1-" + WAIT_MAX_SECONDS + ").",
                    min = 1, max = WAIT_MAX_SECONDS) int seconds,
            @Arg(value = "Optional: why you're waiting (shown on the debug overlay).",
                    required = false) String reason,
            ToolContext ctx) {
        seconds = Math.clamp(seconds, 1, WAIT_MAX_SECONDS);
        String reasonText = reason != null ? reason : "";
        return new WaitTaskRecord(ctx.toolCallId(), ctx.deadline(seconds * 20L + WAIT_DEADLINE_MARGIN_TICKS),
                seconds, reasonText);
    }
}
