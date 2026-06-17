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
        return "Run a SEQUENCE of slot clicks in the GUI you have open (inspect_gui first for indices). "
                + "Pass `clicks` as a list — they run in order, so do a whole operation in ONE call "
                + "(e.g. craft: pick up an ingredient, place it in each cell, take the result).\n"
                + "Each click: {slot, type, button}.\n"
                + "• type=quick_move: shift-click — whole stack to the other section (deposit/take; on a "
                + "crafting result it crafts repeatedly until the grid ingredients run out — put a FULL "
                + "stack in the grid to craft the whole stack at once).\n"
                + "• type=pickup: button=0 pick up / place whole stack; button=1 grab half / place ONE "
                + "(use to put one item per crafting cell).\n"
                + "• type=swap: button = hotbar index 0-8. • type=throw: drop from the slot.\n"
                + "Returns each step's result (slot + cursor after, [NO CHANGE] if a slot refused it). "
                + "Stops at an out-of-range slot.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> click = new LinkedHashMap<>();
        click.put("type", "object");
        click.put("properties", Map.of(
                "slot", Map.of("type", "integer", "description", "Slot index from inspect_gui."),
                "type", Map.of("type", "string", "enum", List.of("quick_move", "pickup", "swap", "throw"),
                        "description", "Click kind."),
                "button", Map.of("type", List.of("integer", "null"),
                        "description", "0 = left (default), 1 = right; for swap = hotbar index 0-8.")));
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

            menu.clicked(slot, button, input, entity);

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
