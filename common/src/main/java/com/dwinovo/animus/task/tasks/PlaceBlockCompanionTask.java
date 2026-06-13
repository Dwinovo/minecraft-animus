package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.InputDriver;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.PlayerInv;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code place_block} on the player body: walk within reach of a target cell and
 * place the requested block there with the player's own survival placement
 * ({@code gameMode.useItemOn} against a support face — vanilla BlockItem.place
 * derives orientation, consumes the item). Player-body twin of PlaceBlockTaskGoal.
 */
public final class PlaceBlockCompanionTask implements CompanionTask {

    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double WALK_SPEED = 1.0;

    private final AnimusPlayer player;
    private final PlaceBlockTaskRecord r;
    private PlayerNav nav;
    private String doneReason = "done";

    public PlaceBlockCompanionTask(AnimusPlayer player, PlaceBlockTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        var level = player.level();
        if (PlayerInv.count(player.getInventory(), r.item) <= 0) {
            fail("no " + r.label + " in inventory to place");
            return;
        }
        if (!BlockHelper.isReplaceableForPlacement(level, r.pos)) {
            fail("target " + coords() + " is already occupied");
            return;
        }
        if (supportClick(r.pos) == null) {
            fail("can't place a floating " + r.label + " at " + coords() + " — nothing adjacent to place against");
            return;
        }
        nav = new PlayerNav(player, r.pos, WALK_SPEED, this::withinReach);
    }

    @Override
    public TaskState tick() {
        if (withinReach()) {
            if (player.level().getBlockState(r.pos).is(r.block)) {
                doneReason = "placed " + r.label + " at " + coords();
                return TaskState.SUCCESS;
            }
            place();
            if (player.level().getBlockState(r.pos).is(r.block)) {
                doneReason = "placed " + r.label + " at " + coords();
                return TaskState.SUCCESS;
            }
            return TaskState.RUNNING;
        }
        if (nav == null) return TaskState.FAILED;
        return switch (nav.tick()) {
            case RUNNING, ARRIVED -> TaskState.RUNNING;
            case FAILED -> {
                doneReason = "can't reach a spot to place at " + coords() + " (" + nav.failReason() + ")";
                yield TaskState.FAILED;
            }
        };
    }

    private void place() {
        int slot = PlayerInv.findSlot(player.getInventory(), r.item);
        if (slot < 0) return;
        BlockHitResult hit = supportClick(r.pos);
        if (hit == null) return;
        ItemStack stack = player.getInventory().getItem(slot);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        InputDriver.lookAt(player, hit.getLocation());
        player.setShiftKeyDown(true);
        try {
            player.gameMode.useItemOn(player, player.level(),
                    player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
        } finally {
            player.setShiftKeyDown(false);
        }
    }

    private BlockHitResult supportClick(BlockPos cell) {
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos neighbour = cell.relative(d);
            if (player.level().getBlockState(neighbour)
                    .getCollisionShape(player.level(), neighbour).isEmpty()) {
                continue;
            }
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(neighbour)
                    .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            return new BlockHitResult(hit, face, neighbour, false);
        }
        return null;
    }

    private boolean withinReach() {
        return player.onGround() && player.distanceToSqr(Vec3.atCenterOf(r.pos)) <= REACH_SQR;
    }

    private String coords() {
        return r.pos.getX() + "," + r.pos.getY() + "," + r.pos.getZ();
    }

    private void fail(String reason) {
        doneReason = reason;
        r.setState(TaskState.FAILED);
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (nav != null) nav.stop();
        Map<String, Object> data = new HashMap<>();
        data.put("block", r.label);
        data.put("x", r.pos.getX());
        data.put("y", r.pos.getY());
        data.put("z", r.pos.getZ());
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case TIMEOUT -> TaskResult.timeout("timed out before placing " + r.label + " at " + coords());
            case CANCELLED -> TaskResult.cancelled("place_block interrupted");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
