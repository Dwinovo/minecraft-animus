package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.InputDriver;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.PlayerInv;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code use_item} on the player body: right-click an item, optionally aimed at a
 * target block, optionally HELD (bow-likes charge over {@code holdTicks}). Runs
 * through the player's own native interaction — {@code gameMode.useItemOn} /
 * {@code useItem} and {@code startUsingItem}/{@code releaseUsingItem} — so any
 * modded item works and consumption/effects land on the companion itself.
 */
public final class UseItemCompanionTask implements CompanionTask {

    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double WALK_SPEED = 1.0;

    private final AnimusPlayer player;
    private final UseItemTaskRecord r;
    private PlayerNav nav;
    private boolean holding = false;
    private int heldSoFar = 0;
    private String doneReason = "done";

    public UseItemCompanionTask(AnimusPlayer player, UseItemTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        if (PlayerInv.count(player.getInventory(), r.item) <= 0) {
            fail("no " + r.label + " in inventory to use");
            return;
        }
        if (r.target != null && !withinReach()) {
            nav = new PlayerNav(player, r.target, WALK_SPEED, this::withinReach);
        }
    }

    @Override
    public TaskState tick() {
        // Approach a target block first.
        if (nav != null && !withinReach()) {
            return switch (nav.tick()) {
                case RUNNING, ARRIVED -> TaskState.RUNNING;
                case FAILED -> {
                    doneReason = "can't reach " + r.label + "'s target (" + nav.failReason() + ")";
                    yield TaskState.FAILED;
                }
            };
        }

        int slot = PlayerInv.findSlot(player.getInventory(), r.item);
        if (slot < 0) {
            fail("no " + r.label + " left to use");
            return TaskState.FAILED;
        }
        ItemStack stack = player.getInventory().getItem(slot);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        // HELD use (bow-like): begin charging, count, release at holdTicks.
        if (r.holdTicks > 1) {
            if (!holding) {
                if (r.target != null) InputDriver.lookAt(player, Vec3.atCenterOf(r.target));
                player.gameMode.useItem(player, player.level(),
                        player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
                holding = player.isUsingItem();
                if (!holding) {   // item has no hold behaviour → fall back to a click
                    doneReason = "used " + r.label;
                    return TaskState.SUCCESS;
                }
                return TaskState.RUNNING;
            }
            if (++heldSoFar < r.holdTicks && player.isUsingItem()) {
                return TaskState.RUNNING;
            }
            player.releaseUsingItem();
            doneReason = "used " + r.label + " (held " + heldSoFar + "t)";
            return TaskState.SUCCESS;
        }

        // Single click — on a block face if a target was given, else in the air.
        if (r.target != null) {
            InputDriver.lookAt(player, Vec3.atCenterOf(r.target));
            BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(r.target), Direction.UP, r.target, false);
            player.gameMode.useItemOn(player, player.level(),
                    player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
        } else {
            player.gameMode.useItem(player, player.level(),
                    player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
        }
        doneReason = "used " + r.label;
        return TaskState.SUCCESS;
    }

    private boolean withinReach() {
        return r.target == null
                || player.distanceToSqr(Vec3.atCenterOf(r.target)) <= REACH_SQR;
    }

    private void fail(String reason) {
        doneReason = reason;
        r.setState(TaskState.FAILED);
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (nav != null) nav.stop();
        if (holding && player.isUsingItem()) player.stopUsingItem();
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case TIMEOUT -> TaskResult.timeout("timed out using " + r.label);
            case CANCELLED -> TaskResult.cancelled("use_item interrupted");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
