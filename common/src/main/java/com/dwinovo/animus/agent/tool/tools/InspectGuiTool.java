package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code inspect_gui} — read the container GUI the companion currently has open (the eyes that make
 * direct GUI manipulation possible). After {@code interact_at} right-clicks a chest/furnace/machine,
 * this lists every slot (index, side, contents, output-only flag) plus the cursor item, so the model
 * can plan {@code click_slot} calls AND verify their effect (error recovery). A read-only server query.
 */
public final class InspectGuiTool implements AnimusTool {

    @Override
    public String name() {
        return "inspect_gui";
    }

    @Override
    public String description() {
        return "Look at the GUI you currently have open. After interact_at right-clicks a chest / "
                + "furnace / machine it shows that container; with NO container open it shows YOUR own "
                + "inventory menu (which includes a 2x2 crafting grid), so you can craft small recipes "
                + "without a table. Lists every slot — index, side, item + count, [output] mark — plus "
                + "the cursor and any machine progress. If a crafting grid is open it draws the grid as a "
                + "2D map of slot numbers, so you place a recipe by dropping its layout onto the same "
                + "positions (no index math). Use it to choose click_slot indices and to verify a click. "
                + "No arguments.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        schema.put("required", List.of());
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return 20;
    }

    @Override
    public boolean isQuery() {
        return true;
    }

    @Override
    public String executeQuery(JsonObject args, AnimusPlayer entity) {
        AbstractContainerMenu menu = entity.containerMenu;
        if (menu == null) {
            return "no GUI open.";
        }
        // With no block menu open, containerMenu IS your own InventoryMenu — which carries the 2x2
        // crafting grid. Surface it so the model can craft small recipes without a table.
        boolean ownInventory = menu == entity.inventoryMenu;
        StringBuilder container = new StringBuilder();
        StringBuilder mine = new StringBuilder();
        // Crafting grid (if any). Detect generically: a slot backed by a CraftingContainer IS a grid
        // cell (vanilla 2x2/3x3 AND modded NxM), the ResultSlot IS the output. We lay the cells out in
        // 2D with their click-able slot numbers so the model can drop the recipe ascii straight onto it
        // — no "row-major + stride + gaps" arithmetic, which is exactly where it kept misplacing.
        int gridW = 0, gridH = 0, resultIndex = -1;
        Slot[] gridCells = null;   // indexed by position-in-container (row-major)
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            boolean playerSide = slot.container == entity.getInventory();
            ItemStack it = slot.getItem();
            if (slot instanceof ResultSlot) {
                resultIndex = slot.index;
                continue;   // shown as part of the crafting-grid section, not the generic dump
            }
            if (slot.container instanceof CraftingContainer cc) {
                if (gridCells == null) {
                    gridW = cc.getWidth();
                    gridH = cc.getHeight();
                    gridCells = new Slot[gridW * gridH];
                }
                int pos = slot.getContainerSlot();
                if (pos >= 0 && pos < gridCells.length) {
                    gridCells[pos] = slot;
                }
                continue;
            }
            // Output-only = a non-empty machine slot that won't take its own item back (result slot).
            boolean output = !playerSide && !it.isEmpty() && !slot.mayPlace(it);
            String line = "  " + i + ": " + describe(it) + (output ? " [output]" : "") + "\n";
            if (playerSide) {
                if (!it.isEmpty()) {
                    mine.append(line);   // only your filled slots — the items you can move in
                }
            } else {
                container.append(line);  // all container slots, empty included (placement targets)
            }
        }
        // Data slots = the menu's OTHER synced channel, parallel to the item slots: the ints a real
        // screen reads to draw progress / fuel / energy bars. Read them generically (no per-menu
        // special-casing) — meaning is GUI-specific, the model/skill interprets (e.g. a furnace's are
        // [litTime, litDuration, cookProgress, cookTotal], so cook% = cookProgress/cookTotal).
        String dataLine = "";
        List<DataSlot> data = ((com.dwinovo.animus.mixin.MenuDataSlotsAccessor) (Object) menu).animus$dataSlots();
        if (!data.isEmpty()) {
            StringBuilder d = new StringBuilder("data values (machine state — progress/fuel/energy/…, "
                    + "meaning is GUI-specific): [");
            for (int i = 0; i < data.size(); i++) {
                if (i > 0) d.append(", ");
                d.append(data.get(i).get());
            }
            dataLine = d.append("]\n").toString();
        }

        // Render the crafting grid as a 2D map of click-able slot numbers, so the recipe ascii from
        // lookup_recipe overlays cell-for-cell (a smaller recipe goes in the TOP-LEFT — same as here).
        String gridSection = "";
        if (gridCells != null) {
            StringBuilder g = new StringBuilder("crafting grid " + gridW + "x" + gridH
                    + " — put each recipe ingredient into the slot at the SAME position (a recipe "
                    + "smaller than the grid goes in the top-left); take the result from slot "
                    + resultIndex + ":\n");
            for (int r = 0; r < gridH; r++) {
                g.append("  ");
                for (int c = 0; c < gridW; c++) {
                    Slot cell = gridCells[r * gridW + c];
                    ItemStack it = cell == null ? ItemStack.EMPTY : cell.getItem();
                    int idx = cell == null ? -1 : cell.index;
                    g.append("slot ").append(idx).append("=").append(describe(it));
                    if (c < gridW - 1) {
                        g.append("  |  ");
                    }
                }
                g.append("\n");
            }
            gridSection = g.toString();
        }

        String header = ownInventory
                ? "GUI: InventoryMenu (YOUR own inventory — includes the 2x2 crafting grid below)\n"
                : "GUI: " + menu.getClass().getSimpleName() + "\n";
        return header
                + gridSection
                + "container slots:\n" + (container.length() == 0 ? "  (none)\n" : container)
                + "your inventory (non-empty):\n" + (mine.length() == 0 ? "  (empty)\n" : mine)
                + "cursor: " + describe(menu.getCarried()) + "\n"
                + dataLine
                + "tip: click_slot type=quick_move shift-moves a whole stack; type=pickup for precise counts.";
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty()
                ? "-"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x" + stack.getCount();
    }
}
