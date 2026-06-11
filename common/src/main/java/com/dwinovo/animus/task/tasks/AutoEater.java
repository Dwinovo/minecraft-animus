package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.TaskResult;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reflex-level self-feeding for combat goals ({@code hunt} / {@code shoot}).
 * An LLM round-trip is far too slow for mid-fight healing, so the goals tick
 * this <em>before</em> their combat logic: when HP drops below
 * {@link #TRIGGER_FRACTION} of max, the entity pauses fighting, chews the most
 * nutritious food it carries (real duration, particles, sound — same mechanics
 * as {@code eat_item} via {@link Eating}), then resumes. The model is not
 * interrupted; everything eaten is folded into the task's final result via
 * {@link #summary()} so it stays informed after the fact.
 *
 * <p>One instance per goal, reused across task records — call {@link #reset()}
 * in {@code onStart}.
 */
final class AutoEater {

    /** Eat when HP falls below this fraction of max (40% = 8 HP on a 20-HP body). */
    private static final float TRIGGER_FRACTION = 0.4f;

    private final AnimusEntity entity;

    private Item eating;
    private int eatTicks;
    private int eatDuration;

    /** item path → count eaten this task, insertion-ordered for stable summaries. */
    private final Map<String, Integer> eaten = new LinkedHashMap<>();
    private float healedTotal;
    private boolean ranOutOfFood;

    AutoEater(AnimusEntity entity) {
        this.entity = entity;
    }

    void reset() {
        eating = null;
        eatTicks = 0;
        eaten.clear();
        healedTotal = 0.0f;
        ranOutOfFood = false;
    }

    /**
     * Tick the reflex. Returns {@code true} while a chew is in progress — the
     * caller should hold its combat logic for that tick (the entity keeps its
     * hands full). Chain-eats on consecutive triggers until HP clears the
     * threshold or the food runs out.
     */
    boolean tick() {
        if (eating != null) {
            eatTicks++;
            if (eatTicks % Eating.EMIT_INTERVAL == 0) {
                Eating.emitChewing(entity, eating);
            }
            if (eatTicks >= eatDuration) {
                float healed = Eating.finishEat(entity, eating);
                if (healed >= 0.0f) {
                    healedTotal += healed;
                    eaten.merge(BuiltInRegistries.ITEM.getKey(eating).getPath(), 1, Integer::sum);
                }
                eating = null;
            }
            return true;
        }

        if (entity.getHealth() > entity.getMaxHealth() * TRIGGER_FRACTION) {
            return false;
        }
        Item food = mostNutritiousFood();
        if (food == null) {
            ranOutOfFood = true;   // noted once; surfaced in summary()
            return false;          // keep fighting — starving is not a pause
        }
        eating = food;
        eatTicks = 0;
        eatDuration = Eating.durationTicks(food);
        return true;
    }

    /** Highest-nutrition edible stack in the inventory, or null. */
    private Item mostNutritiousFood() {
        SimpleContainer inv = entity.getInventory();
        Item best = null;
        int bestNutrition = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) continue;
            if (food.nutrition() > bestNutrition) {
                bestNutrition = food.nutrition();
                best = stack.getItem();
            }
        }
        return best;
    }

    /**
     * One-line account for the task result, or {@code null} when the reflex
     * never fired. E.g. {@code "auto-ate 2x cooked_beef mid-fight (+14 HP)"},
     * plus a loud warning when the trigger hit with an empty pantry.
     */
    String summary() {
        if (eaten.isEmpty() && !ranOutOfFood) return null;
        StringBuilder sb = new StringBuilder();
        if (!eaten.isEmpty()) {
            sb.append("auto-ate ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : eaten.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getValue()).append("x ").append(e.getKey());
                first = false;
            }
            sb.append(" mid-fight (+").append(fmt(healedTotal)).append(" HP)");
        }
        if (ranOutOfFood) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("HP ran low with NO food in inventory — restock cooked food");
        }
        return sb.toString();
    }

    /**
     * Fold post-fight vitals into a combat task's result: appends
     * {@code "; my HP 14/20"} (+ the auto-eat account, if any) to the message
     * and mirrors them in {@code data}. Combat ends, model reads one result,
     * knows exactly what shape its body is in — no extra get_self_status call.
     */
    TaskResult enrich(TaskResult base) {
        Map<String, Object> data = new java.util.HashMap<>(base.data());
        data.put("hp", entity.getHealth());
        data.put("max_hp", entity.getMaxHealth());
        StringBuilder msg = new StringBuilder(base.message());
        msg.append("; my HP ").append(fmt(entity.getHealth()))
                .append("/").append(fmt(entity.getMaxHealth()));
        String fed = summary();
        if (fed != null) {
            data.put("auto_ate", fed);
            msg.append("; ").append(fed);
        }
        return new TaskResult(base.success(), msg.toString(),
                base.timedOut(), base.interrupted(), data);
    }

    private static String fmt(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v);
    }
}
