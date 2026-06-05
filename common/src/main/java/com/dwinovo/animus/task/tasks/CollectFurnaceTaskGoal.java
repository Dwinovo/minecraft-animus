package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.calc.AStar;
import com.dwinovo.animus.pathing.calc.AStarSearch;
import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.calc.Path;
import com.dwinovo.animus.pathing.exec.PathExecutor;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Collector for {@link CollectFurnaceTaskRecord}: walk to the furnace at the
 * given position and take its finished output into the entity inventory, then
 * report what was taken plus the furnace's remaining state.
 *
 * <h2>State machine</h2>
 * <pre>
 *   GOTO_FURNACE → time-sliced A* to the furnace (bridging/digging like move_to);
 *                  in reach → COLLECT.
 *   COLLECT      → empty the result slot into our inventory → SUCCESS (reports
 *                  collected count + remaining input/output state).
 * </pre>
 */
public final class CollectFurnaceTaskGoal extends LlmTaskGoal<CollectFurnaceTaskRecord> {

    private enum Phase { GOTO_FURNACE, COLLECT }

    private static final int NODES_PER_TICK = AStar.DEFAULT_NODES_PER_TICK;
    private static final double WALK_SPEED = 1.0;
    private static final int MAX_REPLANS = 12;

    private final AStar astar = new AStar();

    private Phase phase = Phase.GOTO_FURNACE;
    private AStarSearch search;
    private PathExecutor executor;
    private int replans = 0;

    private String doneReason = "done";
    private final Map<String, Object> resultData = new HashMap<>();

    public CollectFurnaceTaskGoal(AnimusEntity entity) {
        super(entity, CollectFurnaceTaskRecord.TOOL_NAME, CollectFurnaceTaskRecord.class);
    }

    @Override
    protected void onStart(CollectFurnaceTaskRecord r) {
        this.phase = Phase.GOTO_FURNACE;
        this.replans = 0;
        if (FurnaceEngine.furnaceAt(entity.level(), r.pos) == null) {
            fail("no furnace at " + r.pos.getX() + "," + r.pos.getY() + "," + r.pos.getZ());
            return;
        }
        if (withinReach(r.pos)) {
            phase = Phase.COLLECT;
        } else if (!startPlanning(r.pos)) {
            fail("can't reach the furnace");
        }
    }

    @Override
    protected void onTick(CollectFurnaceTaskRecord r) {
        switch (phase) {
            case GOTO_FURNACE -> tickGoto(r);
            case COLLECT -> tickCollect(r);
        }
    }

    private void tickGoto(CollectFurnaceTaskRecord r) {
        if (withinReach(r.pos)) {
            stopExecutor();
            phase = Phase.COLLECT;
            return;
        }
        if (search != null) {
            if (search.step(NODES_PER_TICK) == AStarSearch.State.COMPUTING) {
                return;
            }
            Path path = search.result();
            search = null;
            if (path == null || path.isEmpty()) {
                fail("no path to the furnace");
                return;
            }
            executor = new PathExecutor(entity, path, WALK_SPEED);
            return;
        }
        if (executor == null) {
            fail("no path to the furnace");
            return;
        }
        switch (executor.tick()) {
            case RUNNING -> { /* keep walking */ }
            case ARRIVED, NEEDS_REPLAN -> {
                if (withinReach(r.pos)) {
                    stopExecutor();
                    phase = Phase.COLLECT;
                } else if (!startPlanning(r.pos)) {
                    fail("can't reach the furnace");
                }
            }
            case FAILED -> fail("can't reach the furnace");
        }
    }

    private void tickCollect(CollectFurnaceTaskRecord r) {
        AbstractFurnaceBlockEntity furnace = FurnaceEngine.furnaceAt(entity.level(), r.pos);
        if (furnace == null) {
            fail("furnace vanished before collecting");
            return;
        }
        SimpleContainer inv = entity.getInventory();
        ItemStack output = furnace.getItem(FurnaceEngine.SLOT_RESULT);
        int collected = 0;
        boolean invFull = false;
        String collectedItem = "nothing";
        if (!output.isEmpty()) {
            collectedItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(output.getItem()).toString();
            int before = output.getCount();
            ItemStack leftover = inv.addItem(output.copy());
            collected = before - leftover.getCount();
            invFull = !leftover.isEmpty();
            furnace.setItem(FurnaceEngine.SLOT_RESULT, leftover);
            furnace.setChanged();
            inv.setChanged();
        }

        resultData.putAll(FurnaceEngine.describe(entity.level(), r.pos, furnace));
        resultData.put("collected", collected);

        if (collected > 0) {
            doneReason = "collected " + collected + "x " + collectedItem
                    + (invFull ? " (inventory full — some output left in the furnace)" : "");
        } else {
            int left = resultData.get("items_left_to_smelt") instanceof Number n ? n.intValue() : 0;
            doneReason = left > 0
                    ? "nothing ready yet — still " + left + " smelting"
                    : "furnace output is empty";
        }
        currentRecord.setState(TaskState.SUCCESS);
    }

    // ---- helpers ----

    private boolean withinReach(BlockPos pos) {
        return entity.onGround()
                && entity.distanceToSqr(Vec3.atCenterOf(pos)) <= BlockMiningProgress.REACH_SQR;
    }

    private boolean startPlanning(BlockPos pos) {
        stopExecutor();
        if (replans++ >= MAX_REPLANS) return false;
        NavContext ctx = new NavContext(entity);
        search = astar.newSearch(ctx, entity.blockPosition(), pos);
        return true;
    }

    private void stopExecutor() {
        if (executor != null) {
            executor.stop();
            executor = null;
        }
        search = null;
    }

    private void fail(String reason) {
        doneReason = reason;
        currentRecord.setState(TaskState.FAILED);
    }

    @Override
    protected TaskResult buildResult(CollectFurnaceTaskRecord r, TaskState finalState) {
        stopExecutor();
        entity.getNavigation().stop();
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, resultData);
            case TIMEOUT -> TaskResult.timeout("timed out walking to the furnace");
            case CANCELLED -> TaskResult.cancelled("collect_furnace interrupted");
            case FAILED -> TaskResult.fail(doneReason);
            default -> TaskResult.fail("unexpected state: " + finalState);
        };
    }
}
