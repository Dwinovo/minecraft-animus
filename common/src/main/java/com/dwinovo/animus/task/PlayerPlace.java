package com.dwinovo.animus.task;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.InputDriver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Place a block at {@code cell} through the companion player's own survival
 * placement — pick a solid neighbour face, sneak so a clickable support block's
 * GUI doesn't open, aim and {@code useItemOn}. Vanilla derives orientation +
 * consumes the held item. Shared by place_block / load_furnace / craft (the
 * scaffold/table/furnace placements).
 */
public final class PlayerPlace {

    private PlayerPlace() {}

    /** Place the block in inventory slot {@code slot} at {@code cell}; true if it landed. */
    public static boolean place(AnimusPlayer player, int slot, BlockPos cell) {
        BlockHitResult hit = supportClick(player.level(), cell);
        if (hit == null) return false;
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack.isEmpty()) return false;
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        InputDriver.lookAt(player, hit.getLocation());
        player.setShiftKeyDown(true);
        try {
            player.gameMode.useItemOn(player, player.level(),
                    player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
        } finally {
            player.setShiftKeyDown(false);
        }
        return !player.level().getBlockState(cell).isAir();
    }

    /** A clickable solid neighbour face that places into {@code cell} (below first). */
    public static BlockHitResult supportClick(Level level, BlockPos cell) {
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos neighbour = cell.relative(d);
            if (level.getBlockState(neighbour).getCollisionShape(level, neighbour).isEmpty()) {
                continue;
            }
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(neighbour)
                    .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            return new BlockHitResult(hit, face, neighbour, false);
        }
        return null;
    }
}
