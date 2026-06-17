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
 * {@code transfer} — the convenient layer over raw slot clicks: shift items between slots in the open
 * GUI by intent, never by cursor bookkeeping. Each transfer is {@code {from, to?, count?}}; internally
 * it composes the menu's native {@code clicked} handler (pickup / place / quick_move), so it works on
 * any GUI. Omitting {@code to} routes the stack across (deposit / take / feed a machine); giving
 * {@code to} places it in that exact slot, with empty→move, same-item→merge, different-item→swap. The
 * result tells the model what actually happened to every transfer (amounts moved, merges, swaps, or
 * why a slot refused) — it keeps its eyes on the GUI without a follow-up inspect_gui.
 */
public final class TransferTool implements AnimusTool {

    @Override
    public String name() {
        return "transfer";
    }

    @Override
    public String description() {
        return "Transfer items between slots in the GUI you have open — reorganize, load a machine, "
                + "deposit or take. Pass `moves` as a LIST; they run in order, so do the whole job in "
                + "one call. inspect_gui first for slot indices.\n"
                + "Each transfer: {from, to?, count?}.\n"
                + "• Omit `to` → send from's whole stack to the OTHER section, routed by the menu "
                + "(deposit into a chest, take out, feed a furnace input). The easiest bulk deposit/"
                + "withdraw — you don't pick a destination slot.\n"
                + "• Give `to` → put it in that EXACT slot: empty → moves there; same item → stacks/"
                + "merges; a different item → the two slots SWAP.\n"
                + "• `count` (needs `to`) → move exactly that many instead of the whole stack.\n"
                + "Returns what actually happened to each transfer — amounts, merges, swaps, or why "
                + "nothing moved (full / output-only slot). Crafting → use craft; dropping → drop_items.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> move = new LinkedHashMap<>();
        move.put("type", "object");
        move.put("properties", Map.of(
                "from", Map.of("type", "integer",
                        "description", "Source slot index (from inspect_gui)."),
                "to", Map.of("type", List.of("integer", "null"),
                        "description", "Destination slot. OMIT to route the stack to the other section "
                                + "(deposit/take/feed). Give a slot to place exactly there "
                                + "(empty=move, same item=merge, different item=swap)."),
                "count", Map.of("type", List.of("integer", "null"),
                        "description", "Exact number to move (needs `to`; default = the whole stack). "
                                + "Ignored when `to` is omitted — routing moves the whole stack.")));
        move.put("required", List.of("from", "to", "count"));
        move.put("additionalProperties", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("moves", Map.of("type", "array", "items", move,
                "description", "Transfers to run in order (one whole job per call)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("moves"));
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
            return "no GUI open — interact_at a container or machine first.";
        }
        if (!args.has("moves") || args.get("moves").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: moves (a list of transfers)");
        }
        JsonArray moves = args.getAsJsonArray("moves");
        if (moves.isEmpty()) {
            throw new IllegalArgumentException("moves must contain at least one transfer");
        }

        int max = menu.slots.size() - 1;
        StringBuilder out = new StringBuilder();
        int step = 0;
        for (JsonElement el : moves) {
            step++;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("from") || o.get("from").isJsonNull()) {
                throw new IllegalArgumentException("transfer " + step + ": missing from");
            }
            int from = o.get("from").getAsInt();
            Integer to = (o.has("to") && !o.get("to").isJsonNull()) ? o.get("to").getAsInt() : null;
            Integer count = (o.has("count") && !o.get("count").isJsonNull()) ? o.get("count").getAsInt() : null;

            out.append(step).append(". ");
            if (from < 0 || from > max) {
                out.append("from slot ").append(from).append(" OUT OF RANGE (0..").append(max)
                        .append(") — skipped; inspect_gui for indices.\n");
                continue;
            }
            if (to != null && (to < 0 || to > max)) {
                out.append("to slot ").append(to).append(" OUT OF RANGE (0..").append(max)
                        .append(") — skipped; inspect_gui for indices.\n");
                continue;
            }
            try {
                out.append(to == null ? route(menu, entity, from, count)
                                      : place(menu, entity, from, to, count));
            } catch (RuntimeException ex) {
                out.append("slot ").append(from).append(" — ERROR: ").append(ex.getMessage())
                        .append(" (earlier transfers already applied).");
            }
            out.append("\n");
        }
        return out.toString();
    }

    /** No destination: shift the whole stack to the other section, menu-routed (deposit/take/feed). */
    private static String route(AbstractContainerMenu menu, AnimusPlayer entity, int from, Integer count) {
        ItemStack before = menu.slots.get(from).getItem().copy();
        if (before.isEmpty()) {
            return "slot " + from + " is empty — nothing to move.";
        }
        menu.clicked(from, 0, ContainerInput.QUICK_MOVE, entity);
        ItemStack after = menu.slots.get(from).getItem();
        int moved = before.getCount() - (sameItem(before, after) ? after.getCount() : 0);
        String note = (count != null) ? " (count ignored — routing moves the whole stack; give `to` "
                + "for an exact amount)" : "";
        if (moved <= 0) {
            return "slot " + from + " (" + name(before) + ") didn't move — the other section is full "
                    + "or won't accept it." + note;
        }
        return "routed " + moved + " " + name(before) + " from slot " + from + " to the other section "
                + "(deposit/take/feed)." + (after.isEmpty() ? "" : " " + after.getCount() + " left in slot "
                + from + ".") + note;
    }

    /** A destination slot: place exactly there — empty→move, same item→merge, different item→swap. */
    private static String place(AbstractContainerMenu menu, AnimusPlayer entity, int from, int to, Integer count) {
        if (from == to) {
            return "slot " + from + " → itself — nothing to do.";
        }
        ItemStack fromBefore = menu.slots.get(from).getItem().copy();
        ItemStack toBefore = menu.slots.get(to).getItem().copy();
        if (fromBefore.isEmpty()) {
            return "slot " + from + " is empty — nothing to move.";
        }

        boolean exact = count != null && count > 0;
        boolean differentItem = !toBefore.isEmpty() && !sameItem(toBefore, fromBefore);

        if (exact && differentItem) {
            return "can't move " + count + " from slot " + from + " — slot " + to + " holds "
                    + name(toBefore) + ". Omit count to swap the whole stacks instead.";
        }

        if (exact) {
            int want = Math.min(count, fromBefore.getCount());
            menu.clicked(from, 0, ContainerInput.PICKUP, entity);          // grab the stack
            for (int i = 0; i < want; i++) {
                menu.clicked(to, 1, ContainerInput.PICKUP, entity);        // drop ONE (merges / fills)
            }
            menu.clicked(from, 0, ContainerInput.PICKUP, entity);          // return the remainder
        } else {
            menu.clicked(from, 0, ContainerInput.PICKUP, entity);          // grab the stack
            menu.clicked(to, 0, ContainerInput.PICKUP, entity);            // place / merge / swap
            if (!menu.getCarried().isEmpty()) {
                menu.clicked(from, 0, ContainerInput.PICKUP, entity);      // settle leftover / swapped item back
            }
        }

        ItemStack fromAfter = menu.slots.get(from).getItem();
        ItemStack toAfter = menu.slots.get(to).getItem();
        int moved = fromBefore.getCount() - (sameItem(fromBefore, fromAfter) ? fromAfter.getCount() : 0);

        if (differentItem) {     // whole-stack onto a different item = swap
            if (sameItem(toAfter, fromBefore) && sameItem(fromAfter, toBefore)) {
                return "swapped slot " + from + " (" + name(fromBefore) + ") ⇄ slot " + to + " ("
                        + name(toBefore) + ").";
            }
            return "slot " + from + " → " + to + ": nothing moved — slot " + to + " refused it "
                    + "(output-only slot?).";
        }
        if (moved <= 0) {
            return "slot " + from + " → " + to + ": nothing moved — slot " + to
                    + (toBefore.isEmpty() ? " refused it (output-only slot?)." : " is already full.");
        }
        String verb = toBefore.isEmpty() ? "moved " : "merged ";
        return verb + moved + " " + name(fromBefore) + " from slot " + from + " to slot " + to
                + " (slot " + to + " now " + toAfter.getCount()
                + (fromAfter.isEmpty() ? "; slot " + from + " emptied)." : "; " + fromAfter.getCount()
                + " left in slot " + from + ").");
    }

    private static boolean sameItem(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameComponents(a, b);
    }

    private static String name(ItemStack stack) {
        return stack.isEmpty() ? "nothing" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
}
