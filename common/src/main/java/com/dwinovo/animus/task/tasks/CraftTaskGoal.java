package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.calc.AStar;
import com.dwinovo.animus.pathing.calc.AStarSearch;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.Path;
import com.dwinovo.animus.pathing.exec.PathExecutor;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.pathing.util.BlockScanner;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Intent-level crafter for {@link CraftTaskRecord}: "make N of this item." The
 * LLM only names the item and quantity; this goal owns the whole Voyager-style
 * flow, reusing the mod's existing building blocks:
 *
 * <ul>
 *   <li>{@link CraftingEngine} — reverse-lookup the recipe, match/consume
 *       materials, produce the result (the recipe math).</li>
 *   <li>{@link BlockScanner} — find a nearby crafting table for 3×3 recipes.</li>
 *   <li>{@link AStar} + {@link PathExecutor} — walk to that table, bridging /
 *       digging like {@code move_to} / {@code mine_block}.</li>
 * </ul>
 *
 * <h2>State machine</h2>
 * <pre>
 *   RESOLVE     → reverse-lookup a recipe for the target. None → FAILED.
 *                 2×2 recipe → CRAFT. 3×3 recipe → FIND_TABLE.
 *   FIND_TABLE  → existing table in range? walk to it (GOTO_TABLE) or craft if
 *                 already adjacent. None? place one from inventory beside us, or
 *                 FAILED (no table, none to place).
 *   GOTO_TABLE  → time-sliced A* to the table; in reach → CRAFT.
 *   CRAFT       → craftOnce in a bounded per-tick batch until the count is met
 *                 or materials run out (partial success, missing list reported).
 * </pre>
 *
 * <h2>Voyager parity, no recursion</h2>
 * If an intermediate product is missing (planks for a pickaxe) the task fails
 * with the shortfall listed in the result; the LLM decomposes and re-crafts
 * step by step, exactly like Voyager's {@code craftItem}.
 */
public final class CraftTaskGoal extends LlmTaskGoal<CraftTaskRecord> {

    private enum Phase { RESOLVE, FIND_TABLE, GOTO_TABLE, CRAFT }

    private static final int NODES_PER_TICK = AStar.DEFAULT_NODES_PER_TICK;
    private static final double WALK_SPEED = 1.0;
    /** A table within this many blocks counts as "nearby"; further → place our own. */
    private static final int MAX_REPLANS = 12;
    /** Bound per-tick crafts so a huge count can't stall the server tick. */
    private static final int CRAFTS_PER_TICK = 16;

    private final AStar astar = new AStar();

    private Phase phase = Phase.RESOLVE;
    private CraftingEngine.Plan plan;

    // Crafting-table logistics (3×3 recipes only).
    private BlockPos tablePos;
    private boolean placedTableOurselves = false;
    private AStarSearch search;
    private PathExecutor executor;
    private int replans = 0;

    private String doneReason = "done";

    public CraftTaskGoal(AnimusEntity entity) {
        super(entity, CraftTaskRecord.TOOL_NAME, CraftTaskRecord.class);
    }

    @Override
    protected void onStart(CraftTaskRecord r) {
        this.phase = Phase.RESOLVE;
        this.plan = null;
        this.tablePos = null;
        this.placedTableOurselves = false;
        this.replans = 0;
    }

    @Override
    protected void onTick(CraftTaskRecord r) {
        switch (phase) {
            case RESOLVE -> tickResolve(r);
            case FIND_TABLE -> tickFindTable(r);
            case GOTO_TABLE -> tickGotoTable(r);
            case CRAFT -> tickCraft(r);
        }
    }

    // ---- RESOLVE: reverse-lookup the recipe ----

    private void tickResolve(CraftTaskRecord r) {
        if (!(entity.level() instanceof ServerLevel sl)) {
            fail("not on a server level");
            return;
        }
        plan = CraftingEngine.findRecipe(sl, entity.getInventory(), r.item);
        if (plan == null) {
            fail("no crafting recipe produces " + r.label);
            return;
        }
        if (!CraftingEngine.canCraftOnce(entity.getInventory(), plan.recipe())) {
            String missing = CraftingEngine.describeMissing(entity.getInventory(), plan.recipe());
            fail("missing materials for " + r.label + ": " + missing);
            return;
        }
        phase = plan.needsTable() ? Phase.FIND_TABLE : Phase.CRAFT;
    }

    // ---- FIND_TABLE: locate, walk to, or place a crafting table ----

    private void tickFindTable(CraftTaskRecord r) {
        Level level = entity.level();
        List<BlockScanner.Hit> tables = BlockScanner.findWithin(
                level, entity.blockPosition(), r.tableSearchRadius, Set.of(Blocks.CRAFTING_TABLE));

        if (!tables.isEmpty()) {
            tablePos = tables.get(0).pos();
            if (withinReach(tablePos)) {
                phase = Phase.CRAFT;
            } else if (startPlanning()) {
                phase = Phase.GOTO_TABLE;
            } else {
                fail("can't reach a crafting table");
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

    private void tickGotoTable(CraftTaskRecord r) {
        if (withinReach(tablePos)) {
            stopExecutor();
            phase = Phase.CRAFT;
            return;
        }
        if (search != null) {
            if (search.step(NODES_PER_TICK) == AStarSearch.State.COMPUTING) {
                return;
            }
            Path path = search.result();
            search = null;
            if (path == null || path.isEmpty()) {
                fail("no path to the crafting table");
                return;
            }
            executor = new PathExecutor(entity, path, WALK_SPEED);
            return;
        }
        if (executor == null) {
            fail("no path to the crafting table");
            return;
        }
        switch (executor.tick()) {
            case RUNNING -> { /* keep walking */ }
            case ARRIVED, NEEDS_REPLAN -> {
                if (withinReach(tablePos)) {
                    stopExecutor();
                    phase = Phase.CRAFT;
                } else if (!startPlanning()) {
                    fail("can't reach the crafting table");
                }
            }
            case FAILED -> fail("can't reach the crafting table");
        }
    }

    // ---- CRAFT: consume materials and produce, bounded per tick ----

    private void tickCraft(CraftTaskRecord r) {
        // A table we walked to could have been removed; re-validate before relying on it.
        if (plan.needsTable()
                && entity.level().getBlockState(tablePos).getBlock() != Blocks.CRAFTING_TABLE) {
            phase = Phase.FIND_TABLE;
            return;
        }
        int did = 0;
        while (r.getProduced() < r.count && did < CRAFTS_PER_TICK) {
            ItemStack result = CraftingEngine.craftOnce(entity, plan.recipe());
            if (result == null) {
                // Ran out of materials mid-run — partial success if we made any.
                String missing = CraftingEngine.describeMissing(entity.getInventory(), plan.recipe());
                doneReason = r.getProduced() > 0
                        ? "made " + r.getProduced() + "/" + r.count + ", then ran out (missing " + missing + ")"
                        : "missing materials: " + missing;
                r.setState(r.getProduced() > 0 ? TaskState.SUCCESS : TaskState.FAILED);
                return;
            }
            r.addProduced(result.getCount());
            entity.setDebugTask(r.describe());
            did++;
        }
        if (r.getProduced() >= r.count) {
            doneReason = "crafted " + r.getProduced() + " " + r.label;
            r.setState(TaskState.SUCCESS);
        }
        // else: more to do — continue next tick.
    }

    // ---- helpers ----

    /** Within crafting-table interaction range and standing stably. */
    private boolean withinReach(BlockPos pos) {
        return entity.onGround()
                && entity.distanceToSqr(Vec3.atCenterOf(pos)) <= BlockMiningProgress.REACH_SQR;
    }

    /** Begin/replan a time-sliced search to the table. False when out of replan budget. */
    private boolean startPlanning() {
        stopExecutor();
        if (replans++ >= MAX_REPLANS) return false;
        NavContext ctx = new NavContext(entity);
        search = astar.newSearch(ctx, entity.blockPosition(), tablePos);
        return true;
    }

    private void stopExecutor() {
        if (executor != null) {
            executor.stop();
            executor = null;
        }
        search = null;
    }

    /**
     * Place a carried crafting table on a walkable spot beside the entity.
     * Returns the position placed, or {@code null} if we carry none / no spot.
     */
    private BlockPos placeTableBeside() {
        if (entity.getInventory().countItem(Items.CRAFTING_TABLE) <= 0) {
            return null;
        }
        Level level = entity.level();
        BlockPos feet = entity.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos at = feet.relative(dir);
            if (BlockHelper.isReplaceableForPlacement(level, at)
                    && BlockHelper.canWalkOn(level, at.below())) {
                if (!level.setBlock(at, Blocks.CRAFTING_TABLE.defaultBlockState(), 3)) {
                    continue;
                }
                entity.getInventory().removeItemType(Items.CRAFTING_TABLE, 1);
                entity.getInventory().setChanged();
                playPlaceSound(at);
                return at;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")  // BlockStateBase.getSoundType() is "deprecated for
                                      // override" (Mojang convention), not phased out.
    private void playPlaceSound(BlockPos at) {
        SoundType sound = Blocks.CRAFTING_TABLE.defaultBlockState().getSoundType();
        entity.level().playSound(null, at, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
    }

    private void fail(String reason) {
        doneReason = reason;
        currentRecord.setState(TaskState.FAILED);
    }

    @Override
    protected TaskResult buildResult(CraftTaskRecord r, TaskState finalState) {
        stopExecutor();
        entity.getNavigation().stop();

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
