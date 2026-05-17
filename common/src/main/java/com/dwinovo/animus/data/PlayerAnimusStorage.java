package com.dwinovo.animus.data;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-player virtual chest — 54 slots, shared across the player's PlayerAgent
 * and every EntityAgent. Mining drops bypass the world's ItemEntity stage
 * and land here directly; the GUI lets the player take items back out.
 *
 * <h2>Why a SimpleContainer subclass</h2>
 * Reusing vanilla {@link SimpleContainer} means the existing
 * {@code AbstractContainerMenu} machinery can render the GUI as if it were
 * a chest with no further work — the Storage tab just opens a menu over
 * this container. ItemStack semantics (NBT, durability, enchants) come for
 * free.
 *
 * <h2>Persistence</h2>
 * <strong>Not persisted in MVP.</strong> Held in {@link PlayerAnimusData}
 * which lives in a server-side in-memory map; server restart clears
 * everything. A later commit will lift this to a vanilla {@code SavedData}
 * keyed by player UUID.
 */
public final class PlayerAnimusStorage extends SimpleContainer {

    public static final int CAPACITY = 54;  // double-chest size

    public PlayerAnimusStorage() {
        super(CAPACITY);
    }

    /**
     * Insert one stack, returning the leftover. Pure convenience over
     * vanilla's {@link SimpleContainer#addItem} which mutates in-place and
     * returns a partial leftover stack — same semantics, named clearly.
     *
     * <p>The returned stack is the portion that didn't fit; caller decides
     * what to do (drop on ground at the entity, queue for next tick, fail
     * loudly). Callers that don't care can ignore the return value.
     */
    public ItemStack insert(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        return addItem(stack);
    }

    /**
     * Snapshot the contents for env-block rendering and the {@code get_storage}
     * tool's JSON output. Skips empty slots so the LLM doesn't have to filter.
     */
    public List<SlotSnapshot> snapshotNonEmpty() {
        List<SlotSnapshot> out = new ArrayList<>();
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack stack = getItem(i);
            if (stack.isEmpty()) continue;
            out.add(new SlotSnapshot(i, stack.copy()));
        }
        return out;
    }

    /**
     * Compact view of a non-empty slot.
     *
     * @param slotIndex 0..53
     * @param stack     copy of the underlying ItemStack
     */
    public record SlotSnapshot(int slotIndex, ItemStack stack) {}
}
