package com.dwinovo.animus.task.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;

/**
 * Shared slot mechanics for the chest-storage tools ({@code deposit_items} /
 * {@code take_items}). Operates directly on the block entity's
 * {@link Container} slots on the server tick thread — same model as the
 * furnace tools, no GUI menus involved.
 *
 * <p>Note on double chests: a single half is addressed as its own 27-slot
 * container. Good enough — the model addresses chests by coordinate anyway.
 */
final class ContainerOps {

    /** Storage blocks the tools recognise. Deliberately no ender_chest (player-bound). */
    static final Set<Block> CONTAINER_BLOCKS =
            Set.of(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL);

    private ContainerOps() {}

    static boolean isContainerBlock(Level level, BlockPos pos) {
        return CONTAINER_BLOCKS.contains(level.getBlockState(pos).getBlock());
    }

    static Container containerAt(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof Container c ? c : null;
    }

    /**
     * Move up to {@code count} of {@code item} from the entity inventory into
     * the container (merge into matching stacks first, then empty slots).
     *
     * @return how many were actually moved (0 = container full or none held)
     */
    static int deposit(Container from, Container into, Item item, int count) {
        int toMove = Math.min(count, countIn(from, item));
        int remaining = toMove;
        int maxStack = new ItemStack(item).getMaxStackSize();

        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            boolean mergePass = pass == 0;
            for (int i = 0; i < into.getContainerSize() && remaining > 0; i++) {
                ItemStack slot = into.getItem(i);
                if (mergePass) {
                    if (slot.isEmpty() || slot.getItem() != item) continue;
                    int add = Math.min(remaining, slot.getMaxStackSize() - slot.getCount());
                    if (add <= 0) continue;
                    slot.grow(add);
                    remaining -= add;
                } else if (slot.isEmpty()) {
                    int add = Math.min(remaining, maxStack);
                    into.setItem(i, new ItemStack(item, add));
                    remaining -= add;
                }
            }
        }
        int moved = toMove - remaining;
        if (moved > 0) {
            removeFrom(from, item, moved);
            from.setChanged();
            into.setChanged();
        }
        return moved;
    }

    /** Total count of {@code item} across a container. */
    static int countIn(Container c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** Remove up to {@code count} of {@code item} from a container. */
    static void removeFrom(Container c, Item item, int count) {
        int left = count;
        for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !s.is(item)) continue;
            int take = Math.min(s.getCount(), left);
            s.shrink(take);
            left -= take;
        }
    }

    /** Add {@code stack} to a container (merge then fill); returns the leftover. */
    static ItemStack addTo(Container c, ItemStack stack) {
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
            boolean merge = pass == 0;
            for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
                ItemStack slot = c.getItem(i);
                if (merge) {
                    if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, stack)) continue;
                    int add = Math.min(stack.getCount(), slot.getMaxStackSize() - slot.getCount());
                    if (add <= 0) continue;
                    slot.grow(add);
                    stack.shrink(add);
                } else if (slot.isEmpty()) {
                    int add = Math.min(stack.getCount(), stack.getMaxStackSize());
                    c.setItem(i, stack.copyWithCount(add));
                    stack.shrink(add);
                }
            }
        }
        return stack;
    }

    /**
     * Move up to {@code count} of {@code item} from the container into the
     * entity inventory, stopping cleanly when the inventory fills (overflow is
     * returned to the container slot it came from, never dropped).
     *
     * @return how many actually landed in the entity inventory
     */
    static int extract(Container from, Container into, Item item, int count) {
        int taken = 0;
        for (int i = 0; i < from.getContainerSize() && taken < count; i++) {
            ItemStack slot = from.getItem(i);
            if (slot.isEmpty() || slot.getItem() != item) continue;
            int want = Math.min(count - taken, slot.getCount());
            ItemStack moving = new ItemStack(item, want);
            ItemStack overflow = addTo(into, moving);
            int landed = want - overflow.getCount();
            if (landed <= 0) break;          // entity inventory is full
            slot.shrink(landed);
            if (slot.isEmpty()) from.setItem(i, ItemStack.EMPTY);
            taken += landed;
            if (!overflow.isEmpty()) break;  // partially full — stop here
        }
        if (taken > 0) {
            from.setChanged();
            into.setChanged();
        }
        return taken;
    }
}
