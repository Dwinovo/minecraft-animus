package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
        return "Look at the container GUI you currently have open (after interact_at right-clicks a "
                + "chest / furnace / barrel / machine). Lists every slot — index, side (container vs "
                + "your inventory), item + count, and an [output] mark for slots you can only take "
                + "from — plus the item on your cursor. Use it to choose click_slot indices and to "
                + "check the result of a click. No arguments; returns 'no GUI open' if nothing is open.";
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
        if (menu == null || menu == entity.inventoryMenu) {
            return "no GUI open — interact_at (button=right) a container or machine first.";
        }
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
        // Machine progress lives in the menu's data slots, not the item slots. Vanilla exposes the
        // furnace family's via typed getters; surface it so smelting progress is visible here too.
        String progress = "";
        if (menu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu furnace) {
            progress = "smelt progress: cook " + Math.round(furnace.getBurnProgress() * 100) + "%, fuel "
                    + Math.round(furnace.getLitProgress() * 100) + "% left, lit=" + furnace.isLit() + "\n";
        }

        return "GUI: " + menu.getClass().getSimpleName() + "\n"
                + "container slots:\n" + (container.length() == 0 ? "  (none)\n" : container)
                + "your inventory (non-empty):\n" + (mine.length() == 0 ? "  (empty)\n" : mine)
                + "cursor: " + describe(menu.getCarried()) + "\n"
                + progress
                + "tip: click_slot type=quick_move shift-moves a whole stack; type=pickup for precise counts.";
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty()
                ? "-"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x" + stack.getCount();
    }
}
