package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.phys.Vec3;

/**
 * Shared timed-eating mechanics for the Animus, used by both the explicit
 * {@link EatItemTaskGoal} ({@code eat_item}) and the in-combat reflex
 * {@link AutoEater}. There is no hunger system: eating heals directly,
 * scaled by the food's nutrition, once the chew completes.
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

    /** Chewing particles + sound at the entity's mouth. */
    static void emitChewing(AnimusEntity entity, Item item) {
        if (!(entity.level() instanceof ServerLevel sl)) return;
        Vec3 look = entity.getLookAngle();
        double mx = entity.getX() + look.x * 0.35;
        double my = entity.getEyeY() - 0.2;     // ~mouth height
        double mz = entity.getZ() + look.z * 0.35;
        sl.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, item),
                mx, my, mz, 4, 0.1, 0.1, 0.1, 0.03);
        float pitch = 0.9f + entity.getRandom().nextFloat() * 0.2f;
        sl.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.6f, pitch);
    }

    /**
     * Complete the eat: heal by the food's nutrition, apply its consume effects
     * (e.g. a golden apple's regeneration/absorption), consume one item from the
     * inventory, and recover any leftover container (bowl, bottle).
     *
     * @return HP actually healed (clamped by max health), or {@code -1} if the
     *         item vanished from the inventory before the chew finished.
     */
    static float finishEat(AnimusEntity entity, Item item) {
        SimpleContainer inv = entity.getInventory();
        if (inv.countItem(item) <= 0) return -1.0f;

        ItemStack one = new ItemStack(item);
        float before = entity.getHealth();
        FoodProperties food = one.get(DataComponents.FOOD);
        if (food != null) {
            entity.heal(food.nutrition());
        }
        Consumable consumable = one.get(DataComponents.CONSUMABLE);
        if (consumable != null && entity.level() instanceof ServerLevel sl) {
            ItemStack leftover = consumable.onConsume(sl, entity, one);
            if (!leftover.isEmpty()) {
                ItemStack overflow = inv.addItem(leftover);
                if (!overflow.isEmpty()) entity.spawnAtLocation(sl, overflow);
            }
        }
        inv.removeItemType(item, 1);
        inv.setChanged();
        return entity.getHealth() - before;
    }
}
