package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.core.task.BreakBlockTaskRecord;
import com.dwinovo.numen.core.task.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.InteractEntityTaskRecord;
import com.dwinovo.numen.core.task.MineBlockTaskRecord;
import com.dwinovo.numen.core.task.PlaceBlockTaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Block-action tools authored on the {@link NumenAction} surface — the
 * world-action dogfood for construction and native interaction. Each method
 * validates its args and builds a {@link TaskRecord}; the {@link ToolContext}
 * carries the call id and deadline basis. Behaviour matches the hand-written
 * {@code NumenTool} classes they replace.
 */
public final class BlockActionTools {

    // place_block: covers walking to the spot.
    private static final long PLACE_TIMEOUT_TICKS = 30 * 20;
    // break_block: walk + dig budget; obsidian by hand-tier diamond pick is ~10s alone.
    private static final long BREAK_TIMEOUT_TICKS = 45 * 20;

    // auto_mine budgets / bounds.
    private static final int DEFAULT_MAX_RADIUS = 48;
    private static final int MAX_ALLOWED_RADIUS = 96;
    private static final int MAX_COUNT = 256;
    /** Per-block budget is generous; total scales with count so big jobs don't time out. */
    private static final long TICKS_PER_BLOCK = 30 * 20;   // 30s each
    private static final long MIN_TIMEOUT_TICKS = 60 * 20; // 1 min floor

    // interact_at: covers walking to the aim.
    private static final long INTERACT_AT_TIMEOUT_TICKS = 30 * 20;
    // interact_entity: covers chasing a moving target.
    private static final long INTERACT_ENTITY_TIMEOUT_TICKS = 60 * 20;

    public TaskRecord placeBlock(
String block_id,
int x,
int y,
int z,
String facing,
String axis,
String half,
            ToolContext ctx) {
        Item item = ToolArgs.parseItem(block_id);
        if (!(item instanceof BlockItem blockItem)) {
            throw new IllegalArgumentException(
                    BuiltInRegistries.ITEM.getKey(item) + " is not a placeable block");
        }
        BlockPos pos = new BlockPos(x, y, z);
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        Direction facingDir = optEnum(facing) == null ? null
                : Direction.byName(optEnum(facing));
        Direction.Axis axisVal = optEnum(axis) == null ? null
                : Direction.Axis.byName(optEnum(axis));
        String halfVal = optEnum(half);
        Boolean topHalf = halfVal == null ? null : halfVal.equals("top");
        return new PlaceBlockTaskRecord(ctx.toolCallId(), ctx.deadline(PLACE_TIMEOUT_TICKS),
                blockItem.getBlock(), item, pos, label, facingDir, axisVal, topHalf);
    }

    /** A lowercased optional enum string value, or null if absent / blank. */
    private static String optEnum(String v) {
        if (v == null) return null;
        v = v.trim().toLowerCase();
        return v.isEmpty() ? null : v;
    }

    public TaskRecord breakBlock(
int x,
int y,
int z,
            ToolContext ctx) {
        BlockPos target = new BlockPos(x, y, z);
        return new BreakBlockTaskRecord(ctx.toolCallId(), ctx.deadline(BREAK_TIMEOUT_TICKS), target);
    }

    public TaskRecord autoMine(
            List<String> block_ids,
int count,
Integer radius,
            ToolContext ctx) {
        Set<Block> targets = readBlockIds(block_ids);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("block_ids contained no valid block ids");
        }
        int clampedCount = Math.max(1, Math.min(MAX_COUNT, count));

        int searchRadius = DEFAULT_MAX_RADIUS;
        if (radius != null) {
            searchRadius = radius;
            if (searchRadius < 1) searchRadius = 1;
            if (searchRadius > MAX_ALLOWED_RADIUS) searchRadius = MAX_ALLOWED_RADIUS;
        }

        String label = labelFor(targets);
        long timeout = Math.max(MIN_TIMEOUT_TICKS, (long) clampedCount * TICKS_PER_BLOCK);
        long deadline = ctx.deadline(timeout);
        return new MineBlockTaskRecord(ctx.toolCallId(), deadline, targets, clampedCount, searchRadius, label);
    }

    private static Set<Block> readBlockIds(List<String> blockIds) {
        Set<Block> out = new LinkedHashSet<>();
        for (String el : blockIds) {
            if (el == null) continue;
            ResourceLocation id = ResourceLocation.tryParse(el);
            if (id == null) continue;
            Block b = BuiltInRegistries.BLOCK.get(id);
            if (b != null && b != Blocks.AIR) out.add(b);
        }
        return out;
    }

    /** Short label for messages: the first target's path (e.g. "iron_ore"), "+N" if more. */
    private static String labelFor(Set<Block> targets) {
        Block first = targets.iterator().next();
        String path = BuiltInRegistries.BLOCK.getKey(first).getPath();
        return targets.size() == 1 ? path : path + "+" + (targets.size() - 1);
    }

    public TaskRecord interactAt(
String button,
Integer x,
Integer y,
Integer z,
Integer hold_ticks,
String item_id,
            ToolContext ctx) {
        InteractAtTaskRecord.Button buttonVal = readAtButton(button);
        int holdTicks = hold_ticks == null ? 0 : hold_ticks;

        BlockPos aim = null;
        if (x != null || y != null || z != null) {
            if (x == null || y == null || z == null) {
                throw new IllegalArgumentException(
                        "an aim point needs all of x, y, z (or leave all null to use the held item straight ahead).");
            }
            aim = new BlockPos(x, y, z);
        }
        Item item = item_id == null ? null : ToolArgs.parseItem(item_id);
        String bodyBound = InteractAtTaskRecord.bodyBoundReason(item);
        if (bodyBound != null) {
            throw new IllegalArgumentException(bodyBound);
        }
        return new InteractAtTaskRecord(ctx.toolCallId(), ctx.deadline(INTERACT_AT_TIMEOUT_TICKS), buttonVal, aim, holdTicks, item);
    }

    private static InteractAtTaskRecord.Button readAtButton(String button) {
        if (button == null) {
            throw new IllegalArgumentException("missing required argument: button");
        }
        return switch (button) {
            case "left" -> InteractAtTaskRecord.Button.LEFT;
            case "right" -> InteractAtTaskRecord.Button.RIGHT;
            default -> throw new IllegalArgumentException(
                    "button must be 'left' or 'right', got: " + button);
        };
    }

    public TaskRecord interactEntity(
String button,
int entity_id,
Integer hold_ticks,
String item_id,
            ToolContext ctx) {
        InteractEntityTaskRecord.Button buttonVal = readEntityButton(button);
        int holdTicks = hold_ticks == null ? 0 : hold_ticks;
        return new InteractEntityTaskRecord(ctx.toolCallId(), ctx.deadline(INTERACT_ENTITY_TIMEOUT_TICKS), buttonVal, entity_id, holdTicks,
                item_id == null ? null : ToolArgs.parseItem(item_id));
    }

    private static InteractEntityTaskRecord.Button readEntityButton(String button) {
        if (button == null) {
            throw new IllegalArgumentException("missing required argument: button");
        }
        return switch (button) {
            case "left" -> InteractEntityTaskRecord.Button.LEFT;
            case "right" -> InteractEntityTaskRecord.Button.RIGHT;
            default -> throw new IllegalArgumentException(
                    "button must be 'left' or 'right', got: " + button);
        };
    }
}
