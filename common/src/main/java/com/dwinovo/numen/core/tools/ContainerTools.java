package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Container-GUI tools authored on the {@link NumenAction} surface. {@code transfer}
 * is the first object-array dogfood: its {@code moves} argument binds to a
 * {@code List<Move>}, the adapter deriving the array's item object schema from
 * the {@link Move} record's {@link Arg} components. Behaviour matches the
 * hand-written tool.
 */
public final class ContainerTools {

    /** One transfer; its @Arg components become the {@code moves} array item schema. */
    public record Move(
int from,
Integer to,
Integer count) {}

    public String transfer(
List<Move> moves,
            NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null) {
            return TaskResult.fail("no GUI open — interact_at a container or machine first.").toJson();
        }
        if (moves.isEmpty()) {
            throw new IllegalArgumentException("moves must contain at least one transfer");
        }

        int max = menu.slots.size() - 1;
        StringBuilder out = new StringBuilder();
        int step = 0;
        for (Move m : moves) {
            step++;
            int from = m.from();
            Integer to = m.to();
            Integer count = m.count();

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
                out.append(to == null ? route(menu, self, from, count)
                                      : place(menu, self, from, to, count));
            } catch (RuntimeException ex) {
                out.append("slot ").append(from).append(" — ERROR: ").append(ex.getMessage())
                        .append(" (earlier transfers already applied).");
            }
            out.append("\n");
        }
        return TaskResult.ok(out.toString().stripTrailing()).toJson();
    }

    /** No destination: shift the whole stack to the other section, menu-routed (deposit/take/feed). */
    private static String route(AbstractContainerMenu menu, NumenPlayer entity, int from, Integer count) {
        ItemStack before = menu.slots.get(from).getItem().copy();
        if (before.isEmpty()) {
            return "slot " + from + " is empty — nothing to move.";
        }
        menu.clicked(from, 0, ClickType.QUICK_MOVE, entity);
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
    private static String place(AbstractContainerMenu menu, NumenPlayer entity, int from, int to, Integer count) {
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
            menu.clicked(from, 0, ClickType.PICKUP, entity);          // grab the stack
            for (int i = 0; i < want; i++) {
                menu.clicked(to, 1, ClickType.PICKUP, entity);        // drop ONE (merges / fills)
            }
            menu.clicked(from, 0, ClickType.PICKUP, entity);          // return the remainder
        } else {
            menu.clicked(from, 0, ClickType.PICKUP, entity);          // grab the stack
            menu.clicked(to, 0, ClickType.PICKUP, entity);            // place / merge / swap
            if (!menu.getCarried().isEmpty()) {
                menu.clicked(from, 0, ClickType.PICKUP, entity);      // settle leftover / swapped item back
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
        return ItemStack.isSameItemSameTags(a, b);   // 1.20.4: NBT tags, pre-DataComponents
    }

    private static String name(ItemStack stack) {
        return stack.isEmpty() ? "nothing" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
}
