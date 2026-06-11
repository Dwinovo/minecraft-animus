package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executor for {@link TakeItemsTaskRecord}: walk to a chest/barrel and move
 * items from it into the entity's inventory. On a miss it reports what the
 * container actually holds, so the model can correct course in one turn
 * instead of guessing.
 */
public final class TakeItemsTaskGoal extends AbstractContainerItemGoal<TakeItemsTaskRecord> {

    /** Cap the contents listing on a miss — enough to orient, not a token bomb. */
    private static final int MAX_LISTED_TYPES = 8;

    public TakeItemsTaskGoal(AnimusEntity entity) {
        super(entity, TakeItemsTaskRecord.TOOL_NAME, TakeItemsTaskRecord.class);
    }

    @Override
    protected void operate(TakeItemsTaskRecord r, Container container) {
        int available = countIn(container, r);
        if (available <= 0) {
            fail("no " + r.label + " in the container at " + posLabel()
                    + " — it holds: " + summarise(container));
            return;
        }
        int taken = ContainerOps.extract(container, entity.getInventory(), r.item, r.count);
        if (taken <= 0) {
            fail("my inventory is full — deposit_items or drop_items something first");
            return;
        }
        resultData.put("taken", taken);
        resultData.put("now_carrying", entity.getInventory().countItem(r.item));
        doneReason = "took " + taken + "x " + r.label + " from the container at " + posLabel()
                + (taken < Math.min(r.count, available) ? " — my inventory filled up" :
                   taken < r.count ? " (it only had " + taken + ")" : "");
        currentRecord.setState(TaskState.SUCCESS);
    }

    private static int countIn(Container container, TakeItemsTaskRecord r) {
        int n = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty() && s.getItem() == r.item) n += s.getCount();
        }
        return n;
    }

    /** "64x cobblestone, 12x iron_ingot, …" — or "nothing" for an empty container. */
    private static String summarise(Container container) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (s.isEmpty()) continue;
            counts.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) return "nothing";
        StringBuilder sb = new StringBuilder();
        int listed = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (listed >= MAX_LISTED_TYPES) {
                sb.append(", … (").append(counts.size() - listed).append(" more types)");
                break;
            }
            if (listed > 0) sb.append(", ");
            sb.append(e.getValue()).append("x ").append(e.getKey());
            listed++;
        }
        return sb.toString();
    }
}
