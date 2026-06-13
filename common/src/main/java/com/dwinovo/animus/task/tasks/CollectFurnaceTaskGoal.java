package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Collector for {@link CollectFurnaceTaskRecord}: walk to the furnace at the
 * given position and take its finished output into the companion's inventory,
 * then report what was taken plus the furnace's remaining state.
 *
 * <h2>State machine</h2>
 * <pre>
 *   GOTO_FURNACE → {@link PlayerNav} to the furnace (bridging/digging like move_to);
 *                  in reach → COLLECT.
 *   COLLECT      → empty the result slot into our inventory → SUCCESS (reports
 *                  collected count + remaining input/output state).
 * </pre>
 */
public final class CollectFurnaceTaskGoal implements CompanionTask {

    private enum Phase { GOTO_FURNACE, COLLECT }

    private static final double WALK_SPEED = 1.0;

    private final AnimusPlayer player;
    private final CollectFurnaceTaskRecord r;

    private Phase phase = Phase.GOTO_FURNACE;
    private PlayerNav nav;

    private String doneReason = "done";
    private final Map<String, Object> resultData = new HashMap<>();

    public CollectFurnaceTaskGoal(AnimusPlayer player, CollectFurnaceTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        this.phase = Phase.GOTO_FURNACE;
        this.nav = null;
        if (FurnaceEngine.furnaceAt(player.level(), r.pos) == null) {
            fail("no furnace at " + r.pos.getX() + "," + r.pos.getY() + "," + r.pos.getZ());
            return;
        }
        if (withinReach(r.pos)) {
            phase = Phase.COLLECT;
        } else {
            nav = new PlayerNav(player, r.pos, WALK_SPEED, () -> withinReach(r.pos));
        }
    }

    @Override
    public TaskState tick() {
        switch (phase) {
            case GOTO_FURNACE -> tickGoto();
            case COLLECT -> tickCollect();
        }
        return r.getState();
    }

    private void tickGoto() {
        if (withinReach(r.pos)) {
            stopNav();
            phase = Phase.COLLECT;
            return;
        }
        if (nav == null) {
            fail("can't reach the furnace");
            return;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* keep walking */ }
            case ARRIVED -> {
                stopNav();
                phase = Phase.COLLECT;
            }
            case FAILED -> {
                if (withinReach(r.pos)) {
                    stopNav();
                    phase = Phase.COLLECT;
                } else {
                    fail("can't reach the furnace: " + nav.failReason());
                }
            }
        }
    }

    private void tickCollect() {
        AbstractFurnaceBlockEntity furnace = FurnaceEngine.furnaceAt(player.level(), r.pos);
        if (furnace == null) {
            fail("furnace vanished before collecting");
            return;
        }
        Inventory inv = player.getInventory();
        ItemStack output = furnace.getItem(FurnaceEngine.SLOT_RESULT);
        int collected = 0;
        boolean invFull = false;
        String collectedItem = "nothing";
        if (!output.isEmpty()) {
            collectedItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(output.getItem()).toString();
            int before = output.getCount();
            ItemStack leftover = ContainerOps.addTo(inv, output.copy());
            collected = before - leftover.getCount();
            invFull = !leftover.isEmpty();
            furnace.setItem(FurnaceEngine.SLOT_RESULT, leftover);
            furnace.setChanged();
        }

        resultData.putAll(FurnaceEngine.describe(player.level(), r.pos, furnace));
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
        r.setState(TaskState.SUCCESS);
    }

    // ---- helpers ----

    private boolean withinReach(BlockPos pos) {
        return player.onGround()
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= BlockMiningProgress.REACH_SQR;
    }

    private void stopNav() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
    }

    private void fail(String reason) {
        doneReason = reason;
        r.setState(TaskState.FAILED);
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        stopNav();
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, resultData);
            case TIMEOUT -> TaskResult.timeout("timed out walking to the furnace");
            case CANCELLED -> TaskResult.cancelled("collect_furnace interrupted");
            case FAILED -> TaskResult.fail(doneReason);
            default -> TaskResult.fail("unexpected state: " + finalState);
        };
    }
}
