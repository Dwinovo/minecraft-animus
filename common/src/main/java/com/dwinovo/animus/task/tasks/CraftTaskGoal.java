package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.pathing.util.BlockScanner;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.ContainerSession;
import com.dwinovo.animus.task.PlayerInv;
import com.dwinovo.animus.task.PlayerPlace;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Intent-level crafter for {@link CraftTaskRecord}: "make N of this item." The
 * LLM only names the item and quantity; this task owns the whole Voyager-style
 * flow, reusing the mod's existing building blocks:
 *
 * <ul>
 *   <li>{@link CraftingEngine} — reverse-lookup the recipe, match/consume
 *       materials, produce the result (the recipe math).</li>
 *   <li>{@link BlockScanner} — find a nearby crafting table for 3×3 recipes.</li>
 *   <li>{@link PlayerNav} — walk to that table, bridging / digging like
 *       {@code move_to} / {@code auto_mine}, planned while moving.</li>
 * </ul>
 *
 * <h2>State machine</h2>
 * <pre>
 *   RESOLVE     → reverse-lookup a recipe for the target. None → FAILED.
 *                 2×2 recipe → CRAFT. 3×3 recipe → FIND_TABLE.
 *   FIND_TABLE  → existing table in range? walk to it (GOTO_TABLE) or craft if
 *                 already adjacent. None? place one from inventory beside us, or
 *                 FAILED (no table, none to place).
 *   GOTO_TABLE  → {@link PlayerNav} to the table; in reach → CRAFT.
 *   CRAFT       → craftOnce in a bounded per-tick batch until the count is met
 *                 or materials run out (partial success, missing list reported).
 * </pre>
 *
 * <h2>Voyager parity, no recursion</h2>
 * If an intermediate product is missing (planks for a pickaxe) the task fails
 * with the shortfall listed in the result; the LLM decomposes and re-crafts
 * step by step, exactly like Voyager's {@code craftItem}.
 */
public final class CraftTaskGoal implements CompanionTask {

    private enum Phase { RESOLVE, FIND_TABLE, GOTO_TABLE, CRAFT }

    private static final double WALK_SPEED = 1.0;
    /** Bound per-tick crafts so a huge count can't stall the server tick. */
    private static final int CRAFTS_PER_TICK = 16;
    /** Give up opening the table after this many ticks of no line of sight (don't spin to timeout). */
    private static final int MAX_OPEN_ATTEMPTS = 30;

    private final AnimusPlayer player;
    private final CraftTaskRecord r;

    private Phase phase = Phase.RESOLVE;
    private CraftingEngine.Plan plan;

    // Crafting-table logistics (3×3 recipes only).
    private BlockPos tablePos;
    private boolean placedTableOurselves = false;
    private PlayerNav nav;
    /** The real table-menu session for a 3×3 craft (so observer mods see open/craft/close);
     *  idle for 2×2 inventory crafting. */
    private final ContainerSession session;
    private int openAttempts;

    private String doneReason = "done";

    public CraftTaskGoal(AnimusPlayer player, CraftTaskRecord record) {
        this.player = player;
        this.r = record;
        this.session = new ContainerSession(player);
    }

    @Override
    public void start() {
        this.phase = Phase.RESOLVE;
        this.plan = null;
        this.tablePos = null;
        this.placedTableOurselves = false;
        this.nav = null;
        this.session.close();
        this.openAttempts = 0;
    }

    @Override
    public TaskState tick() {
        switch (phase) {
            case RESOLVE -> tickResolve();
            case FIND_TABLE -> tickFindTable();
            case GOTO_TABLE -> tickGotoTable();
            case CRAFT -> tickCraft();
        }
        return r.getState();
    }

    // ---- RESOLVE: reverse-lookup the recipe ----

    private void tickResolve() {
        if (!(player.level() instanceof ServerLevel sl)) {
            fail("not on a server level");
            return;
        }
        plan = CraftingEngine.findRecipe(sl, player.getInventory(), r.item);
        if (plan == null) {
            fail("no crafting recipe produces " + r.label);
            return;
        }
        if (!CraftingEngine.canCraftOnce(player.getInventory(), plan.recipe())) {
            String missing = CraftingEngine.describeMissing(player.getInventory(), plan.recipe());
            fail("missing materials for " + r.label + ": " + missing);
            return;
        }
        phase = plan.needsTable() ? Phase.FIND_TABLE : Phase.CRAFT;
    }

    // ---- FIND_TABLE: locate, walk to, or place a crafting table ----

    private void tickFindTable() {
        Level level = player.level();
        List<BlockScanner.Hit> tables = BlockScanner.findWithin(
                level, player.blockPosition(), r.tableSearchRadius, Set.of(Blocks.CRAFTING_TABLE));

        if (!tables.isEmpty()) {
            tablePos = tables.get(0).pos();
            if (withinReach(tablePos)) {
                phase = Phase.CRAFT;
            } else {
                nav = new PlayerNav(player, tablePos, WALK_SPEED, () -> withinReach(tablePos));
                phase = Phase.GOTO_TABLE;
            }
            return;
        }

        // None nearby — place our own if we carry one.
        BlockPos spot = placeTableBeside();
        if (spot != null) {
            tablePos = spot;
            placedTableOurselves = true;
            phase = Phase.CRAFT;          // placed adjacent → already in reach
        } else {
            fail("no crafting table nearby and none in inventory to place");
        }
    }

    // ---- GOTO_TABLE: drive the pathfinder to the table ----

    private void tickGotoTable() {
        switch (nav.tick()) {
            case RUNNING -> { /* keep walking; planned while moving */ }
            case ARRIVED -> { nav.stop(); phase = Phase.CRAFT; }
            case FAILED -> {
                if (withinReach(tablePos)) {
                    nav.stop();
                    phase = Phase.CRAFT;
                } else {
                    fail("can't reach the crafting table");
                }
            }
        }
    }

    // ---- CRAFT: consume materials and produce, bounded per tick ----

    private void tickCraft() {
        // 2×2 has no separate container to observe (it's the player's own inventory grid), so it
        // stays the fast virtual path. 3×3 opens the table's REAL menu so observer mods see it.
        if (!plan.needsTable()) {
            tickCraftVirtual();
            return;
        }
        // A table we walked to could have been removed; re-validate before relying on it.
        if (player.level().getBlockState(tablePos).getBlock() != Blocks.CRAFTING_TABLE) {
            session.close();
            phase = Phase.FIND_TABLE;
            return;
        }
        if (!(player.level() instanceof ServerLevel sl)) {
            session.close();
            fail("not on a server level");
            return;
        }
        // Open the table's real menu once (a genuine right-click on the table → menu open). Retry a
        // bounded number of ticks if the line of sight is blocked, then fail honestly.
        if (!session.open(tablePos)) {
            if (++openAttempts > MAX_OPEN_ATTEMPTS) {
                fail("couldn't open the crafting table at " + tablePos.getX() + ","
                        + tablePos.getY() + "," + tablePos.getZ() + " (no clear line of sight to it)");
            }
            return;
        }
        openAttempts = 0;
        int did = 0;
        while (r.getProduced() < r.count && did < CRAFTS_PER_TICK) {
            ItemStack result = session.craftOnce(plan.holder(), sl);
            if (result.isEmpty()) {                 // couldn't fill the grid → out of materials
                String missing = CraftingEngine.describeMissing(player.getInventory(), plan.recipe());
                int made = r.getProduced();
                session.close();
                doneReason = made > 0
                        ? "made " + made + "/" + r.count + ", then ran out (missing " + missing + ")"
                        : "missing materials: " + missing;
                r.setState(made > 0 ? TaskState.SUCCESS : TaskState.FAILED);
                return;
            }
            r.addProduced(result.getCount());
            player.setDebugTask(r.describe());
            did++;
        }
        if (r.getProduced() >= r.count) {
            session.close();
            doneReason = "crafted " + r.getProduced() + " " + r.label;
            r.setState(TaskState.SUCCESS);
        }
        // else: more to do — continue next tick (menu stays open across the batch).
    }

    /** 2×2 inventory crafting — the original virtual path (no GUI to open). */
    private void tickCraftVirtual() {
        int did = 0;
        while (r.getProduced() < r.count && did < CRAFTS_PER_TICK) {
            ItemStack result = CraftingEngine.craftOnce(player, plan.recipe());
            if (result == null) {
                String missing = CraftingEngine.describeMissing(player.getInventory(), plan.recipe());
                doneReason = r.getProduced() > 0
                        ? "made " + r.getProduced() + "/" + r.count + ", then ran out (missing " + missing + ")"
                        : "missing materials: " + missing;
                r.setState(r.getProduced() > 0 ? TaskState.SUCCESS : TaskState.FAILED);
                return;
            }
            r.addProduced(result.getCount());
            player.setDebugTask(r.describe());
            did++;
        }
        if (r.getProduced() >= r.count) {
            doneReason = "crafted " + r.getProduced() + " " + r.label;
            r.setState(TaskState.SUCCESS);
        }
    }


    // ---- helpers ----

    /** Within crafting-table interaction range and standing stably. */
    private boolean withinReach(BlockPos pos) {
        return player.onGround()
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= BlockMiningProgress.REACH_SQR;
    }

    /**
     * Place a carried crafting table on a walkable spot beside the companion.
     * Returns the position placed, or {@code null} if we carry none / no spot.
     */
    private BlockPos placeTableBeside() {
        int slot = PlayerInv.findSlot(player.getInventory(), Items.CRAFTING_TABLE);
        if (slot < 0) {
            return null;
        }
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos at = feet.relative(dir);
            if (BlockHelper.isReplaceableForPlacement(level, at)
                    && BlockHelper.canWalkOn(level, at.below())) {
                // The companion places it itself (survival placement): vanilla
                // rules, sound/events, and consumption accounted inside.
                if (PlayerPlace.place(player, slot, at)) {
                    return at;
                }
            }
        }
        return null;
    }

    private void fail(String reason) {
        doneReason = reason;
        r.setState(TaskState.FAILED);
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (nav != null) nav.stop();
        session.close();   // never leave the table menu open (timeout / cancel land here too)

        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("requested", r.count);
        data.put("produced", r.getProduced());
        // Report the crafting table used (found or placed). A table we placed is
        // intentionally LEFT in the world — not recovered — so it can be reused.
        // Surfacing its coords lets the model (and a future entity-memory layer)
        // remember where a table is instead of placing another each time.
        String tableNote = "";
        if (tablePos != null) {
            data.put("crafting_table_x", tablePos.getX());
            data.put("crafting_table_y", tablePos.getY());
            data.put("crafting_table_z", tablePos.getZ());
            if (placedTableOurselves) {
                data.put("placed_crafting_table", true);
                tableNote = " — left a crafting table at " + tablePos.getX() + ","
                        + tablePos.getY() + "," + tablePos.getZ() + " (reuse it next time)";
            }
        }

        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(
                    "crafted " + r.getProduced() + "/" + r.count + " " + r.label
                            + " (" + doneReason + ")" + tableNote,
                    data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out after crafting " + r.getProduced() + "/" + r.count + " " + r.label,
                    true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "interrupted after crafting " + r.getProduced() + "/" + r.count + " " + r.label,
                    false, true, data);
            case FAILED -> TaskResult.fail(doneReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
