package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code click_slot} — click a slot in the open container GUI. The universal, precise item-move: it
 * runs the menu's own server-side click handler ({@code AbstractContainerMenu.clicked}, the exact code
 * a real player's click triggers), so it works on ANY GUI without knowing slot layouts. A synchronous
 * server query that returns the cursor + clicked slot afterwards, so the model sees the effect and can
 * recover. Pair with {@code inspect_gui} for slot indices and verification.
 */
public final class ClickSlotTool implements AnimusTool {

    @Override
    public String name() {
        return "click_slot";
    }

    @Override
    public String description() {
        return "Click a slot in the container GUI you have open (inspect_gui first for slot indices). "
                + "The precise, universal way to move items in any GUI.\n"
                + "• type=quick_move (default): shift-click — moves the whole stack to the other "
                + "section (fast deposit/take).\n"
                + "• type=pickup: pick up onto / place from the cursor — button=0 = whole stack / "
                + "place all; button=1 = grab half / place one (use for exact counts).\n"
                + "• type=swap: button = a hotbar index 0-8; swaps this slot with that hotbar slot.\n"
                + "• type=throw: drop from the slot (button=1 drops the whole stack).\n"
                + "Returns the cursor + this slot after the click. Errors if no GUI is open.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("slot", Map.of("type", "integer",
                "description", "Slot index from inspect_gui."));
        properties.put("type", Map.of("type", "string",
                "enum", List.of("quick_move", "pickup", "swap", "throw"),
                "description", "Click kind (default quick_move)."));
        properties.put("button", Map.of("type", List.of("integer", "null"),
                "description", "0 = left (default), 1 = right; for swap = hotbar index 0-8."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("slot", "type", "button"));
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
            return "no GUI open — interact_at (button=right) a container first.";
        }
        if (!args.has("slot") || args.get("slot").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: slot");
        }
        int slot = args.get("slot").getAsInt();
        if (slot < 0 || slot >= menu.slots.size()) {
            throw new IllegalArgumentException("slot " + slot + " out of range 0.." + (menu.slots.size() - 1)
                    + " — inspect_gui for valid indices");
        }
        ContainerInput input = readType(args);
        int button = (args.has("button") && !args.get("button").isJsonNull())
                ? args.get("button").getAsInt() : 0;

        ItemStack beforeSlot = menu.slots.get(slot).getItem().copy();
        ItemStack beforeCursor = menu.getCarried().copy();

        menu.clicked(slot, button, input, entity);

        ItemStack now = menu.slots.get(slot).getItem();
        ItemStack cursor = menu.getCarried();
        // The menu's own rules (Slot.mayPlace) can refuse a move — e.g. raw iron into a furnace's
        // fuel slot. Then nothing changes (item stays on the cursor, slot untouched). Flag that
        // explicitly so the model knows it was BLOCKED rather than assuming it worked.
        boolean blocked = ItemStack.matches(beforeSlot, now) && ItemStack.matches(beforeCursor, cursor);
        String note = blocked
                ? " — NO CHANGE: that slot refused the move (wrong item type for this machine slot, "
                        + "the slot is full, or it's output-only). Try a different slot or container."
                : "";
        return "clicked slot " + slot + " (" + input.name().toLowerCase() + ", button " + button + "): "
                + "slot now " + describe(now) + ", cursor " + describe(cursor) + note;
    }

    private static ContainerInput readType(JsonObject args) {
        if (!args.has("type") || args.get("type").isJsonNull()) {
            return ContainerInput.QUICK_MOVE;
        }
        return switch (args.get("type").getAsString()) {
            case "quick_move" -> ContainerInput.QUICK_MOVE;
            case "pickup" -> ContainerInput.PICKUP;
            case "swap" -> ContainerInput.SWAP;
            case "throw" -> ContainerInput.THROW;
            default -> throw new IllegalArgumentException(
                    "type must be quick_move|pickup|swap|throw, got: " + args.get("type").getAsString());
        };
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty()
                ? "-"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x" + stack.getCount();
    }
}
