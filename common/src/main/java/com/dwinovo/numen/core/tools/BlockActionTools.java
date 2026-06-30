package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.agent.tool.api.Arg;
import com.dwinovo.numen.agent.tool.api.NumenAction;
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

    @NumenAction(name = "place_block", timeoutTicks = PLACE_TIMEOUT_TICKS, description =
            "Place a block from your inventory at an absolute coordinate. The "
            + "companion travels to a reachable spot next to the target on its own "
            + "— digging through obstacles, bridging gaps, pillaring up — then "
            + "places it like a real player (steps to the edge, looks, places). "
            + "The coordinate is the cell the block will OCCUPY, not the block it "
            + "sits on — to put a torch on top of a block at (x,y,z), target "
            + "(x,y+1,z). Placement still needs a block to attach to (you can't "
            + "place in pure mid-air), and the target cell must be empty. "
            + "Optional orientation for blocks that have one: `facing` "
            + "(north/south/east/west/up/down — furnace/chest/stairs/observer…), "
            + "`axis` (x/y/z — logs/pillars), `half` (top/bottom — slabs/stairs). "
            + "The result reports the block's ACTUAL orientation, so if it differs "
            + "from what you asked, break it and retry from another angle. Fails "
            + "with guidance (incl. nearby coords that WOULD work) if you lack the "
            + "block, it isn't placeable, the target is occupied, or there's no "
            + "reachable spot — so don't place where a block already is; build "
            + "somewhere clear instead. Use for torches, walls/shelter, sealing "
            + "caves, or positioning a crafting table/furnace/chest.")
    public TaskRecord placeBlock(
            @Arg("Namespaced id of the block item to place, e.g. minecraft:torch.") String block_id,
            @Arg("Target x.") int x,
            @Arg("Target y.") int y,
            @Arg("Target z.") int z,
            @Arg(value = "Optional. Which way the block should face (furnace/chest/stairs/…).",
                    required = false, nullable = true, enumValues = {"north", "south", "east", "west", "up", "down"}) String facing,
            @Arg(value = "Optional. Pillar/log axis (y = upright).",
                    required = false, nullable = true, enumValues = {"x", "y", "z"}) String axis,
            @Arg(value = "Optional. Which half for a slab / stairs.",
                    required = false, nullable = true, enumValues = {"top", "bottom"}) String half,
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

    @NumenAction(name = "break_block", timeoutTicks = BREAK_TIMEOUT_TICKS, description =
            "Break the ONE block at exact coordinates — the precision inverse "
            + "of place_block, for construction work: clear the cell a "
            + "structure block must occupy, remove a block placed by mistake, "
            + "prune obstructions. The entity walks within reach first. "
            + "Drops are collected into your inventory. Requires the right "
            + "tool in hand for blocks that need one (same rule as "
            + "auto_mine — stone needs a pickaxe); fails with guidance "
            + "otherwise. To GATHER resources by type, use auto_mine "
            + "instead — it finds blocks itself.")
    public TaskRecord breakBlock(
            @Arg("Block X.") int x,
            @Arg("Block Y.") int y,
            @Arg("Block Z.") int z,
            ToolContext ctx) {
        BlockPos target = new BlockPos(x, y, z);
        return new BreakBlockTaskRecord(ctx.toolCallId(), ctx.deadline(BREAK_TIMEOUT_TICKS), target);
    }

    @NumenAction(name = "auto_mine", timeoutTicks = MIN_TIMEOUT_TICKS, description =
            "Gather blocks by type and quantity. Give the block id(s) and how "
            + "many you want — the entity finds the nearest ones and travels "
            + "to each with full terrain-traversing navigation: it digs "
            + "tunnels to reach buried ores, pillars up to blocks high on "
            + "cliffs, and bridges gaps with cobblestone/dirt from its own "
            + "inventory, all automatically — then mines them and repeats "
            + "until it has gathered `count` of the resulting ITEMS or none "
            + "remain nearby. count is items, not blocks: a block can drop "
            + "several (redstone_ore → ~4 redstone), so count:10 redstone "
            + "mines only ~3 ore. It counts only NEW items gained, on top of "
            + "what you already carry. You do NOT "
            + "provide coordinates, call move_to, or pre-clear a path; "
            + "carrying some cobblestone/dirt helps it cross terrain. "
            + "Include all variants of a resource in block_ids "
            + "(e.g. iron_ore AND deepslate_iron_ore). Optional radius caps "
            + "how far to look (default auto-expands). Returns the actual "
            + "number gathered, which may be less than requested if the deposit "
            + "runs out. You must hold a tool that can harvest the target: "
            + "mining a block your main-hand tool can't harvest fails up front "
            + "and tells you the minimum tier required (e.g. iron_ore needs a "
            + "stone pickaxe). Equip the right pickaxe/axe/shovel yourself first "
            + "(equip_item) — check get_self_status for your main hand.")
    public TaskRecord autoMine(
            @Arg(value = "Namespaced block id(s) to gather; include all variants.", minItems = 1)
            List<String> block_ids,
            @Arg(value = "How many ITEMS to gather (not blocks) — a block may drop several, "
                    + "and it counts only items gained on top of what you already hold.",
                    min = 1, max = MAX_COUNT) int count,
            @Arg(value = "Optional max search radius in blocks (default auto-expands to "
                    + DEFAULT_MAX_RADIUS + ").",
                    required = false, min = 1, max = MAX_ALLOWED_RADIUS) Integer radius,
            ToolContext ctx) {
        Set<Block> targets = readBlockIds(block_ids);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("block_ids contained no valid block ids");
        }
        int clampedCount = Math.clamp(count, 1, MAX_COUNT);

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

    @NumenAction(name = "interact_at", timeoutTicks = INTERACT_AT_TIMEOUT_TICKS, description =
            "Aim at a world point and press one mouse button — the native crosshair "
            + "interaction for BLOCKS and the AIR (moving entities use interact_entity). "
            + "Auto-paths within reach like move_to, then a raytrace resolves what's under the aim.\n"
            + "• button=right (use): activate / OPEN the block at x,y,z. To USE a station — a "
            + "chest, furnace, crafting table, barrel, or any block with a GUI — aim at its "
            + "coordinate here: interact_at paths you within reach AND opens its menu. NEVER "
            + "move_to a station to 'go to it' — you'd just path into the block; interact_at is "
            + "the one step that both walks there and opens it. Also flips levers/buttons/doors/"
            + "beds, or USEs an item ON the block — e.g. bonemeal a crop, an "
            + "ender_eye on an end_portal_frame to fill it. LIGHT A NETHER PORTAL: flint_and_steel "
            + "aimed at an EMPTY AIR cell INSIDE the obsidian frame (not the obsidian). BUCKETS: "
            + "an empty bucket on a SOURCE water/lava cell fills it; a full bucket on the cell to "
            + "pour into empties it. Omit x,y,z (or aim at clear air) to use the held item in the "
            + "air — e.g. throw an ender_eye to locate a stronghold, lob a snowball.\n"
            + "• button=left (attack): break the block at x,y,z (native timed; the right tool must "
            + "be in inventory). Left-click on air does nothing.\n"
            + "item_id: optionally the item to use — it is equipped to the hand first, so you "
            + "don't need a separate equip_item. Omit to use whatever is already in hand.\n"
            + "hold_ticks: 0 = a single press; >0 = HOLD that many ticks (a modded machine that "
            + "needs continuous right-click, or a bow draw — 20 fully charges a bow); -1 = hold "
            + "until it finishes or the task times out. For routine breaking/placing prefer "
            + "break_block/place_block; eating/drinking is eat_item (not this).")
    public TaskRecord interactAt(
            @Arg(value = "right = use/activate/throw, left = attack/break.",
                    enumValues = {"left", "right"}) String button,
            @Arg(value = "Aim X. Null (with y,z null) = use the held item straight ahead (eat/drink).",
                    nullable = true) Integer x,
            @Arg(value = "Aim Y. Null when aiming forward.", nullable = true) Integer y,
            @Arg(value = "Aim Z. Null when aiming forward.", nullable = true) Integer z,
            @Arg(value = "0/null = single press; >0 = hold that many ticks; -1 = hold until done/timeout.",
                    nullable = true) Integer hold_ticks,
            @Arg(value = "Optional namespaced item to equip-and-use, e.g. minecraft:bonemeal. Null = use what's in hand.",
                    nullable = true) String item_id,
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

    @NumenAction(name = "interact_entity", timeoutTicks = INTERACT_ENTITY_TIMEOUT_TICKS, description =
            "Press a mouse button on an ENTITY — the native interaction for moving targets. "
            + "Auto-paths and FOLLOWS the entity (id from scan_nearby_entities), then acts once "
            + "the crosshair reaches it (a wall in the way makes it re-position, not hit "
            + "through).\n"
            + "• button=left (attack): hit it. hold_ticks=-1 keeps hitting until it dies "
            + "(killing a mob); 0 = a single hit.\n"
            + "• button=right (use): interact with the held item — trade a villager, breed/feed "
            + "(wheat/seeds/carrots), shear a sheep, name with a name_tag, saddle. hold_ticks>0 "
            + "for a modded entity needing continuous right-click.\n"
            + "item_id: optionally the item to equip-and-use first (the food / shears / weapon), "
            + "so you don't need a separate equip_item. Omit to use what's in hand.\n"
            + "hold_ticks: 0 = one press; >0 = hold that many ticks; -1 = hold until done "
            + "(dead / finished) or timeout. Use interact_at for blocks and thrown items.")
    public TaskRecord interactEntity(
            @Arg(value = "left = attack/hit, right = use/interact.",
                    enumValues = {"left", "right"}) String button,
            @Arg("Target entity id (from scan_nearby_entities).") int entity_id,
            @Arg(value = "0/null = single press; >0 = hold that many ticks; -1 = hold until done/timeout (e.g. attack until dead).",
                    nullable = true) Integer hold_ticks,
            @Arg(value = "Optional namespaced item to equip-and-use, e.g. minecraft:wheat. Null = use what's in hand.",
                    nullable = true) String item_id,
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
