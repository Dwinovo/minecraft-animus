package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
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

    public TaskRecord equipItem(
String item_id,
String slot,
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

    public TaskRecord eatItem(
String item_id,
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

    public TaskRecord dropItems(
String item_id,
int count,
            ToolContext ctx) {
        Item item = ToolArgs.parseItem(item_id);
        count = Math.max(1, Math.min(DROP_MAX_COUNT, count));
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new DropItemsTaskRecord(ctx.toolCallId(), ctx.deadline(DROP_TIMEOUT_TICKS),
                item, count, label);
    }

    public TaskRecord collectItems(
List<String> item_ids,
Integer radius,
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

    public TaskRecord wait(
int seconds,
String reason,
            ToolContext ctx) {
        seconds = Math.max(1, Math.min(WAIT_MAX_SECONDS, seconds));
        String reasonText = reason != null ? reason : "";
        return new WaitTaskRecord(ctx.toolCallId(), ctx.deadline(seconds * 20L + WAIT_DEADLINE_MARGIN_TICKS),
                seconds, reasonText);
    }
}
