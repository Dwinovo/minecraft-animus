package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
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
                + "inventory menu — which includes the 2x2 crafting grid (slots 1-4, result slot 0), so "
                + "you can craft small recipes without a table. Lists every slot — index, side, item + "
                + "count, [output] mark — plus the cursor and any machine progress. Use it to choose "
                + "click_slot indices and to verify a click. No arguments.";
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
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            boolean playerSide = slot.container == entity.getInventory();
            ItemStack it = slot.getItem();
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

        String header = ownInventory
                ? "GUI: InventoryMenu (YOUR own inventory — the 2x2 crafting grid is container slots 1-4, "
                        + "result = slot 0; put ingredients in 1-4, take the result from 0)\n"
                : "GUI: " + menu.getClass().getSimpleName() + "\n";
        return header
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
