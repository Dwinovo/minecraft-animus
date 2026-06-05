package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Eats a food for {@link EatItemTaskRecord} as a real, timed action: chewing
 * particles + sound play over the food's consume duration, and the heal + the
 * food's effects only apply once eating <em>completes</em>. Interrupting it
 * (cancel / timeout / knocked away) wastes nothing — no heal, no item consumed.
 *
 * <h2>Why not use_item</h2>
 * {@code use_item} routes through the loader's fake player, so eating there would
 * feed the fake player, not the Animus. This goal applies everything directly to
 * the Animus.
 */
public final class EatItemTaskGoal extends LlmTaskGoal<EatItemTaskRecord> {

    /** Default eat duration when a food has no explicit consume time (vanilla 1.6s). */
    private static final int DEFAULT_EAT_TICKS = 32;
    /** Emit chewing particles + sound every N ticks. */
    private static final int EMIT_INTERVAL = 4;

    private int eatTicks;
    private int eatDuration;
    private int foodGained;
    private String doneReason = "done";

    public EatItemTaskGoal(AnimusEntity entity) {
        super(entity, EatItemTaskRecord.TOOL_NAME, EatItemTaskRecord.class);
    }

    @Override
    protected void onStart(EatItemTaskRecord r) {
        this.eatTicks = 0;
        this.foodGained = 0;
        if (!(entity.level() instanceof ServerLevel)) {
            fail("not on a server level");
            return;
        }
        if (entity.getInventory().countItem(r.item) <= 0) {
            fail("no " + r.label + " in inventory to eat");
            return;
        }
        ItemStack sample = new ItemStack(r.item);
        if (sample.get(DataComponents.FOOD) == null) {
            fail(r.label + " is not edible");
            return;
        }
        Consumable consumable = sample.get(DataComponents.CONSUMABLE);
        this.eatDuration = consumable != null
                ? Math.max(1, Math.round(consumable.consumeSeconds() * 20.0f))
                : DEFAULT_EAT_TICKS;
        // Stay RUNNING and chew it down over onTick.
    }

    @Override
    protected void onTick(EatItemTaskRecord r) {
        eatTicks++;
        // Chewing feedback partway through, on an interval (skip tick 0).
        if (eatTicks % EMIT_INTERVAL == 0 && entity.level() instanceof ServerLevel sl) {
            emitChewing(sl, r);
        }
        if (eatTicks >= eatDuration) {
            finishEating(r);
        }
    }

    private void emitChewing(ServerLevel sl, EatItemTaskRecord r) {
        Vec3 look = entity.getLookAngle();
        double mx = entity.getX() + look.x * 0.35;
        double my = entity.getEyeY() - 0.2;     // ~mouth height
        double mz = entity.getZ() + look.z * 0.35;
        sl.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, r.item),
                mx, my, mz, 4, 0.1, 0.1, 0.1, 0.03);
        float pitch = 0.9f + entity.getRandom().nextFloat() * 0.2f;
        sl.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.6f, pitch);
    }

    private void finishEating(EatItemTaskRecord r) {
        SimpleContainer inv = entity.getInventory();
        if (inv.countItem(r.item) <= 0) {
            fail("the " + r.label + " was gone before I finished eating");
            return;
        }
        ItemStack one = new ItemStack(r.item);
        FoodProperties food = one.get(DataComponents.FOOD);
        // Fill the hunger bar (NOT an instant heal) — health regenerates over time
        // while well-fed. eat() applies the vanilla nutrition + saturation formula.
        int foodBefore = entity.foodData().getFoodLevel();
        if (food != null) {
            entity.foodData().eat(food);
        }
        foodGained = entity.foodData().getFoodLevel() - foodBefore;

        // Apply the food's consume effects (regen/absorption/etc.) directly to the
        // Animus, and recover any container the food leaves (bowl, bottle).
        Consumable consumable = one.get(DataComponents.CONSUMABLE);
        if (consumable != null && entity.level() instanceof ServerLevel sl) {
            ItemStack leftover = consumable.onConsume(sl, entity, one);
            if (!leftover.isEmpty()) {
                ItemStack overflow = inv.addItem(leftover);
                if (!overflow.isEmpty()) entity.spawnAtLocation(sl, overflow);
            }
        }

        inv.removeItemType(r.item, 1);
        inv.setChanged();
        int fl = entity.foodData().getFoodLevel();
        doneReason = "ate " + r.label + " — food " + fl + "/20"
                + (foodGained > 0 ? " (+" + foodGained + ")" : " (already full)")
                + "; heals over time while well-fed";
        currentRecord.setState(TaskState.SUCCESS);
    }

    private void fail(String reason) {
        doneReason = reason;
        currentRecord.setState(TaskState.FAILED);
    }

    @Override
    protected TaskResult buildResult(EatItemTaskRecord r, TaskState finalState) {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        if (finalState == TaskState.SUCCESS) {
            data.put("food_level", entity.foodData().getFoodLevel());
            data.put("food_gained", foodGained);
            return TaskResult.ok(doneReason, data);
        }
        return switch (finalState) {
            // Interrupted mid-bite → nothing consumed, no heal (vanilla semantics).
            case TIMEOUT -> TaskResult.timeout("couldn't finish eating " + r.label);
            case CANCELLED -> TaskResult.cancelled("eating " + r.label + " interrupted — no effect");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
