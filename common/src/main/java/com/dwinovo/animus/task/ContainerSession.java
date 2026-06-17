package com.dwinovo.animus.task;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.Interaction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.function.Predicate;

/**
 * A real container-menu session for the fake-player body — the container twin of {@link Interaction}
 * (which is the mouse/crosshair twin). One generic primitive for ANY block that opens a menu:
 * crafting table, furnace, chest, modded machine.
 *
 * <p>The trick is that <b>slot indices are never needed</b>. Open the block's menu with a genuine
 * right-click, then move items by the menu's OWN rules: vanilla's {@code quickMoveStack} (shift-click)
 * routes a deposited item to wherever that menu wants it (smeltable → input slot, fuel → fuel slot,
 * the modded machine's input), and the same code modded machines implement drives them too. Crafting
 * GRIDS fill via the recipe-book placement, which reads the recipe's own shape. Because everything
 * goes through the real menu, observer / replay mods see the activity (open → move → take → close).
 *
 * <p>In-world processing (Create belts / basins) has no menu and is out of scope — those use the
 * block/item tools, not this.
 */
public final class ContainerSession {

    private final AnimusPlayer player;
    private AbstractContainerMenu menu;

    public ContainerSession(AnimusPlayer player) {
        this.player = player;
    }

    /** Right-click {@code pos} to open its menu (a real interaction). True once a BLOCK menu is open
     *  (i.e. not the player's own default inventory menu); false if it didn't open this tick. */
    public boolean open(BlockPos pos) {
        if (isOpen()) {
            return true;
        }
        Interaction.useBlock(player, pos, InteractionHand.MAIN_HAND).tick();
        AbstractContainerMenu m = player.containerMenu;
        if (m == null || m == player.inventoryMenu) {
            menu = null;
            return false;
        }
        menu = m;
        return true;
    }

    public boolean isOpen() {
        return menu != null && player.containerMenu == menu;
    }

    public AbstractContainerMenu menu() {
        return menu;
    }

    /**
     * THE primitive — replay ONE GUI click through the menu's own server-side handler
     * ({@link AbstractContainerMenu#clicked}), the exact code path the server runs for a real player's
     * click (and what Carpet fake players drive containers with). {@code input} is the click kind
     * ({@link ContainerInput#QUICK_MOVE} shift-move, {@link ContainerInput#PICKUP} pick-up/place, …);
     * {@code button} 0 = left, 1 = right. Every move below is composed from this, so a modded menu's
     * own {@code clicked}/{@code doClick} logic runs too. The {@code slot} is one we DISCOVER from the
     * live menu (by which container it belongs to + its contents), never a hardcoded layout index.
     */
    public void click(int slot, int button, ContainerInput input) {
        if (isOpen()) {
            menu.clicked(slot, button, input, player);
        }
    }

    /**
     * Shift-click player-inventory stacks matching {@code which} INTO the machine, until EXACTLY
     * {@code max} items have moved (or nothing more matches). The menu routes each to the slot it
     * belongs in (smeltable → input, fuel → fuel, the modded machine's input).
     *
     * <p>To land a precise count we split the last stack: stash its excess in a free player slot so
     * the slot holds exactly the remainder, then shift it. (If the inventory is completely full so
     * there's nowhere to stash, that last stack moves whole — a sub-one-stack overshoot, the only
     * case it isn't exact.) Returns the number of items actually moved into the machine.
     */
    public int deposit(Predicate<ItemStack> which, int max) {
        if (!isOpen() || max <= 0) {
            return 0;
        }
        Inventory inv = player.getInventory();
        int moved = 0;
        for (int i = 0; i < menu.slots.size() && moved < max; i++) {
            Slot s = menu.slots.get(i);
            if (s.container != inv) {
                continue;   // player side only
            }
            if (!s.hasItem() || !which.test(s.getItem())) {
                continue;
            }
            ItemStack stack = s.getItem();
            int want = max - moved;
            if (stack.getCount() > want) {
                // Split: move the part we DON'T want out to a free slot, leaving exactly `want` to shift.
                int free = firstFreeStorageSlot(inv);
                if (free >= 0) {
                    inv.setItem(free, stack.copyWithCount(stack.getCount() - want));
                    s.set(stack.copyWithCount(want));
                }
                // else: nowhere to stash → shift the whole stack (best-effort, slight overshoot).
            }
            int before = s.getItem().getCount();
            click(i, 0, ContainerInput.QUICK_MOVE);
            moved += before - (s.hasItem() ? s.getItem().getCount() : 0);
        }
        return moved;
    }

    /** First empty hotbar/backpack slot (0..35), or -1 — used to stash a split remainder. Skips
     *  armor/offhand so we never shove loose items into an equipment slot. */
    private int firstFreeStorageSlot(Inventory inv) {
        int n = Math.min(36, inv.getContainerSize());
        for (int i = 0; i < n; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** Shift-click ALL machine-side stacks matching {@code which} OUT into the inventory (e.g. empty a
     *  furnace's result slot). Returns moved. */
    public int withdraw(Predicate<ItemStack> which) {
        return withdraw(which, Integer.MAX_VALUE);
    }

    /**
     * Shift-click machine-side stacks matching {@code which} OUT into the inventory, until EXACTLY
     * {@code max} items have moved. Like {@link #deposit}, the last stack is split — its excess stashed
     * in a free machine slot — so the count is exact (whole-stack overshoot only if the machine has no
     * free slot to stash into, e.g. a single-output furnace). Returns the number moved out.
     */
    public int withdraw(Predicate<ItemStack> which, int max) {
        if (!isOpen() || max <= 0) {
            return 0;
        }
        int moved = 0;
        for (int i = 0; i < menu.slots.size() && moved < max; i++) {
            Slot s = menu.slots.get(i);
            if (s.container == player.getInventory()) {
                continue;   // machine side only
            }
            if (!s.hasItem() || !which.test(s.getItem())) {
                continue;
            }
            ItemStack stack = s.getItem();
            int want = max - moved;
            if (stack.getCount() > want) {
                int free = freeMachineSlotFor(stack, i);
                if (free >= 0) {
                    menu.slots.get(free).set(stack.copyWithCount(stack.getCount() - want));
                    s.set(stack.copyWithCount(want));
                }
                // else: nothing to stash the remainder in → take the whole stack (slight overshoot).
            }
            int before = s.getItem().getCount();
            click(i, 0, ContainerInput.QUICK_MOVE);
            moved += before - (s.hasItem() ? s.getItem().getCount() : 0);
        }
        return moved;
    }

    /**
     * Take everything out of the machine's OUTPUT slots — those that refuse item placement
     * ({@code mayPlace == false}, i.e. a furnace result slot or a machine's product slot), leaving
     * inputs and fuel untouched even when they hold the SAME item as the output (smelting logs into
     * charcoal while burning charcoal as fuel). Shift-clicking a result slot also awards the smelt XP.
     * Slot-index free — it keys off the slot's own role, so it works for any machine. Returns moved.
     */
    public int collectOutputs() {
        if (!isOpen()) {
            return 0;
        }
        int moved = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s.container == player.getInventory() || !s.hasItem()) {
                continue;
            }
            if (s.mayPlace(s.getItem())) {
                continue;   // a normal in/out slot (chest cell, furnace input/fuel) — not an output
            }
            int before = s.getItem().getCount();
            click(i, 0, ContainerInput.QUICK_MOVE);
            moved += before - (s.hasItem() ? s.getItem().getCount() : 0);
        }
        return moved;
    }

    /** An empty machine-side slot (other than {@code exceptIndex}) that may hold {@code stack} — used to
     *  stash a split remainder when taking a precise count out. -1 if none. */
    private int freeMachineSlotFor(ItemStack stack, int exceptIndex) {
        for (int i = 0; i < menu.slots.size(); i++) {
            if (i == exceptIndex) {
                continue;
            }
            Slot s = menu.slots.get(i);
            if (s.container != player.getInventory() && !s.hasItem() && s.mayPlace(stack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Fill a crafting grid with {@code holder}'s recipe (vanilla recipe-book auto-fill — shape-correct,
     * mod-aware) and take ONE result into the inventory. Returns the crafted stack, or EMPTY if the grid
     * couldn't be filled (out of materials). The open menu must be a {@link RecipeBookMenu} (crafting).
     */
    public ItemStack craftOnce(RecipeHolder<CraftingRecipe> holder, ServerLevel level) {
        if (!(menu instanceof RecipeBookMenu rbm)) {
            return ItemStack.EMPTY;
        }
        rbm.handlePlacement(false, false, holder, level, player.getInventory());
        ItemStack result = menu.getSlot(CraftingMenu.RESULT_SLOT).getItem();
        if (result.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack crafted = result.copy();
        click(CraftingMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE);   // shift-click result → inventory
        return crafted;
    }

    /** Close the menu (returns any leftovers to the inventory, fires the close event). Idempotent. */
    public void close() {
        if (menu != null) {
            player.closeContainer();
            menu = null;
        }
    }
}
