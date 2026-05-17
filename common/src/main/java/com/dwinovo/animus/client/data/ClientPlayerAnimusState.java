package com.dwinovo.animus.client.data;

import com.dwinovo.animus.Constants;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Client-side mirror of the local player's {@code PlayerAnimusData}.
 * Updated by S→C snapshot payloads; read by the PlayerAgent env-block
 * builder, the GUI, and any local tools that need to surface state.
 *
 * <h2>Why a mirror</h2>
 * The server is authoritative — but PlayerAgent runs on the client and
 * needs unit metadata (names, model keys, alive/active flags) and storage
 * contents synchronously when composing each turn's prompt. Round-tripping
 * to the server every turn would add latency and complicate the agent
 * loop. The mirror trades a tiny memory cost for synchronous reads.
 *
 * <h2>What's mirrored</h2>
 * <ul>
 *   <li>6 {@link ClientUnitView} slots (name / model / alive / active).</li>
 *   <li>Storage contents — flat {@link ItemStack} array, slot-indexed, 54
 *       entries. Empty slots hold {@link ItemStack#EMPTY}.</li>
 * </ul>
 *
 * <h2>Singleton</h2>
 * One per client process. Cleared on disconnect via
 * {@link com.dwinovo.animus.client.agent.AgentLoopRegistry#clear}.
 */
public final class ClientPlayerAnimusState {

    public static final int SLOT_COUNT = 6;
    public static final int STORAGE_CAPACITY = 54;

    private static ClientPlayerAnimusState INSTANCE;

    private final ClientUnitView[] units = new ClientUnitView[SLOT_COUNT];
    private final ItemStack[] storage = new ItemStack[STORAGE_CAPACITY];
    private final List<Consumer<ClientPlayerAnimusState>> listeners = new ArrayList<>();

    private ClientPlayerAnimusState() {
        for (int i = 0; i < SLOT_COUNT; i++) units[i] = ClientUnitView.defaultFor(i + 1);
        Arrays.fill(storage, ItemStack.EMPTY);
    }

    public static synchronized ClientPlayerAnimusState instance() {
        if (INSTANCE == null) INSTANCE = new ClientPlayerAnimusState();
        return INSTANCE;
    }

    public ClientUnitView unit(int unitId) {
        if (unitId < 1 || unitId > SLOT_COUNT) {
            return ClientUnitView.defaultFor(unitId);
        }
        return units[unitId - 1];
    }

    public ClientUnitView[] allUnits() {
        return units;
    }

    public void setUnit(int unitId, ClientUnitView view) {
        if (unitId < 1 || unitId > SLOT_COUNT) return;
        units[unitId - 1] = view;
        notifyChanged();
    }

    public ItemStack storageSlot(int slot) {
        if (slot < 0 || slot >= STORAGE_CAPACITY) return ItemStack.EMPTY;
        return storage[slot];
    }

    public ItemStack[] storageSnapshot() {
        ItemStack[] copy = new ItemStack[STORAGE_CAPACITY];
        for (int i = 0; i < STORAGE_CAPACITY; i++) copy[i] = storage[i].copy();
        return copy;
    }

    public void setStorageSlot(int slot, ItemStack stack) {
        if (slot < 0 || slot >= STORAGE_CAPACITY) return;
        storage[slot] = stack == null ? ItemStack.EMPTY : stack;
    }

    public void setStorageAll(ItemStack[] stacks) {
        for (int i = 0; i < STORAGE_CAPACITY; i++) {
            storage[i] = i < stacks.length && stacks[i] != null ? stacks[i] : ItemStack.EMPTY;
        }
        notifyChanged();
    }

    public void addListener(Consumer<ClientPlayerAnimusState> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<ClientPlayerAnimusState> listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        for (Consumer<ClientPlayerAnimusState> l : listeners) {
            try {
                l.accept(this);
            } catch (RuntimeException ex) {
                Constants.LOG.warn("[animus-state] listener threw: {}", ex.toString());
            }
        }
    }

    public static synchronized void reset() {
        INSTANCE = null;
    }
}
