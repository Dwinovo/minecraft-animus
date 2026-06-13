package com.dwinovo.animus.task.tasks;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;

/**
 * Shared food predicates for the companion's {@link EatCompanionTask}. The
 * timed chew / heal / consume loop is inlined in that task over the player
 * {@code Inventory}; this class only answers the two item-level questions that
 * are pure functions of the food itself.
 */
final class Eating {

    /** Default eat duration when a food has no explicit consume time (vanilla 1.6s). */
    static final int DEFAULT_EAT_TICKS = 32;
    /** Emit chewing particles + sound every N ticks. */
    static final int EMIT_INTERVAL = 4;

    private Eating() {}

    static boolean isEdible(Item item) {
        return new ItemStack(item).get(DataComponents.FOOD) != null;
    }

    /** Chew time in ticks for this food (its consume duration, or the vanilla default). */
    static int durationTicks(Item item) {
        Consumable consumable = new ItemStack(item).get(DataComponents.CONSUMABLE);
        return consumable != null
                ? Math.max(1, Math.round(consumable.consumeSeconds() * 20.0f))
                : DEFAULT_EAT_TICKS;
    }
}
