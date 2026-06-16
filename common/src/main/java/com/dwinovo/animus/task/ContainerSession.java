package com.dwinovo.animus.task;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.Interaction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
     * Shift-click player-inventory stacks matching {@code which} INTO the machine, until {@code max}
     * items have moved (or nothing more matches). The menu routes each to the slot it belongs in.
     * Whole stacks move at a time, so the count can overshoot {@code max} by less than one stack —
     * fine for furnaces/chests; precise single-slot counts would need the click protocol. Returns moved.
     */
    public int deposit(Predicate<ItemStack> which, int max) {
        if (!isOpen() || max <= 0) {
            return 0;
        }
        int moved = 0;
        for (int i = 0; i < menu.slots.size() && moved < max; i++) {
            Slot s = menu.slots.get(i);
            if (s.container != player.getInventory()) {
                continue;   // player side only
            }
            if (!s.hasItem() || !which.test(s.getItem())) {
                continue;
            }
            int before = s.getItem().getCount();
            menu.quickMoveStack(player, i);
            moved += before - (s.hasItem() ? s.getItem().getCount() : 0);
        }
        return moved;
    }

    /** Shift-click machine-side stacks matching {@code which} OUT into the inventory. Returns moved. */
    public int withdraw(Predicate<ItemStack> which) {
        if (!isOpen()) {
            return 0;
        }
        int moved = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s.container == player.getInventory()) {
                continue;   // machine side only
            }
            if (!s.hasItem() || !which.test(s.getItem())) {
                continue;
            }
            int before = s.getItem().getCount();
            menu.quickMoveStack(player, i);
            moved += before - (s.hasItem() ? s.getItem().getCount() : 0);
        }
        return moved;
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
        menu.quickMoveStack(player, CraftingMenu.RESULT_SLOT);   // shift-click result → inventory
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
