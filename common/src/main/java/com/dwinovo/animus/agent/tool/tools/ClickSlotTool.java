package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code click_slot} — run a SEQUENCE of clicks in the open container GUI in one call. Each click runs
 * the menu's own server-side handler ({@code AbstractContainerMenu.clicked}, exactly what a real
 * player's click triggers), so it works on any GUI without knowing slot layouts. Batching the whole
 * operation (e.g. an entire craft: place every ingredient + take the result) into one call collapses
 * what would be a dozen round-trips into one. Returns a per-step log so the model still sees every
 * result and any refused move. Pair with {@code inspect_gui} for slot indices + verification.
 */
public final class ClickSlotTool implements AnimusTool {

    @Override
    public String name() {
        return "click_slot";
    }

    @Override
    public String description() {
        return "Move items in the GUI you have open by clicking its slots (inspect_gui first for "
                + "indices). Pass `clicks` as a LIST — they run in order, so do a whole operation "
                + "(deposit several stacks, take an exact count) in ONE call. For crafting use the craft "
                + "tool instead; reach for click_slot to move items, load a furnace (input + fuel), drive "
                + "a custom modded machine, or fix things up.\n"
                + "Each click: {slot, type, button}.\n"
                + "• type=quick_move: shift-click — sends the slot's whole stack to the other section, "
                + "routed by the menu (deposit/take; a smeltable to a furnace input, etc.).\n"
                + "• type=pickup: button=0 pick up / place the whole stack; button=1 grab half / place "
                + "ONE.\n"
                + "• type=swap: button = hotbar index 0-8 (equip from the slot). • type=throw: button=0 "
                + "drops ONE, button=1 drops the whole stack (your cursor must be empty).\n"
                + "Returns each step's result (slot + cursor after, [NO CHANGE] if a slot refused it). "
                + "Stops at an out-of-range or errored click (earlier steps still applied).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> click = new LinkedHashMap<>();
        click.put("type", "object");
        click.put("properties", Map.of(
                "slot", Map.of("type", "integer", "description", "Slot index from inspect_gui."),
                "type", Map.of("type", "string", "enum", List.of("quick_move", "pickup", "swap", "throw"),
                        "description", "What the click does. "
                                + "quick_move = shift-click: send this slot's WHOLE stack to the other "
                                + "section, routed by the menu (deposit/take); button ignored. "
                                + "pickup = pick up onto / place from the cursor. "
                                + "swap = exchange this slot with a hotbar slot. "
                                + "throw = drop items out of this slot (only when the cursor is empty)."),
                "button", Map.of("type", List.of("integer", "null"),
                        "description", "Meaning depends on `type` (default 0). "
                                + "pickup: 0 = pick up / place the WHOLE stack (left click), "
                                + "1 = grab half / place exactly ONE (right click). "
                                + "swap: the hotbar slot index 0-8 to swap with. "
                                + "throw: 0 = drop ONE, 1 = drop the whole stack. "
                                + "quick_move: ignored.")));
        click.put("required", List.of("slot", "type", "button"));
        click.put("additionalProperties", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("clicks", Map.of("type", "array", "items", click,
                "description", "Clicks to run in order (one whole operation per call)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("clicks"));
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
        if (!args.has("clicks") || args.get("clicks").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: clicks (a list of clicks)");
        }
        JsonArray clicks = args.getAsJsonArray("clicks");
        if (clicks.isEmpty()) {
            throw new IllegalArgumentException("clicks must contain at least one click");
        }

        StringBuilder out = new StringBuilder();
        int step = 0;
        for (JsonElement el : clicks) {
            step++;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("slot") || o.get("slot").isJsonNull()) {
                throw new IllegalArgumentException("click " + step + ": missing slot");
            }
            int slot = o.get("slot").getAsInt();
            if (slot < 0 || slot >= menu.slots.size()) {
                out.append(step).append(". slot ").append(slot).append(" OUT OF RANGE (0..")
                        .append(menu.slots.size() - 1).append(") — stopped here; inspect_gui for indices\n");
                break;
            }
            ContainerInput input = readType(o);
            int button = (o.has("button") && !o.get("button").isJsonNull()) ? o.get("button").getAsInt() : 0;

            ItemStack beforeSlot = menu.slots.get(slot).getItem().copy();
            ItemStack beforeCursor = menu.getCarried().copy();

            try {
                menu.clicked(slot, button, input, entity);
            } catch (RuntimeException ex) {
                // clicked() mutates the menu step-by-step; a throwing click (e.g. a finicky modded
                // slot) must not discard the log of the steps that already applied. Report + stop.
                out.append(step).append(". ").append(input.name().toLowerCase()).append(" slot ")
                        .append(slot).append(" — ERROR: ").append(ex.getMessage())
                        .append(" (stopped here; earlier steps already applied)\n");
                break;
            }

            ItemStack now = menu.slots.get(slot).getItem();
            ItemStack cursor = menu.getCarried();
            boolean blocked = ItemStack.matches(beforeSlot, now) && ItemStack.matches(beforeCursor, cursor);
            out.append(step).append(". ").append(input.name().toLowerCase())
                    .append(button == 1 ? "(right)" : "").append(" slot ").append(slot)
                    .append(" -> slot=").append(describe(now)).append(", cursor=").append(describe(cursor));
            if (blocked) {
                out.append("  [NO CHANGE — that slot refused the move]");
            }
            out.append("\n");
        }
        return out.toString();
    }

    private static ContainerInput readType(JsonObject click) {
        if (!click.has("type") || click.get("type").isJsonNull()) {
            return ContainerInput.QUICK_MOVE;
        }
        return switch (click.get("type").getAsString()) {
            case "quick_move" -> ContainerInput.QUICK_MOVE;
            case "pickup" -> ContainerInput.PICKUP;
            case "swap" -> ContainerInput.SWAP;
            case "throw" -> ContainerInput.THROW;
            default -> throw new IllegalArgumentException(
                    "type must be quick_move|pickup|swap|throw, got: " + click.get("type").getAsString());
        };
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty()
                ? "-"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x" + stack.getCount();
    }
}
