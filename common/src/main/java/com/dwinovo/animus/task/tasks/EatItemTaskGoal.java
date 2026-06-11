package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;

import java.util.HashMap;
import java.util.Map;

/**
 * Eats a food for {@link EatItemTaskRecord} as a real, timed action: chewing
 * particles + sound play over the food's consume duration, and the heal + the
 * food's effects only apply once eating <em>completes</em>. Interrupting it
 * (cancel / timeout / knocked away) wastes nothing — no heal, no item consumed.
 *
 * <p>There is no hunger system: the heal lands directly, scaled by the food's
 * nutrition (mechanics shared with the combat reflex in {@link Eating} /
 * {@link AutoEater}). Eating at full HP is refused up front so food isn't
 * wasted.
 *
 * <h2>Why not use_item</h2>
 * {@code use_item} routes through the loader's fake player, so eating there would
 * feed the fake player, not the Animus. This goal applies everything directly to
 * the Animus.
 */
public final class EatItemTaskGoal extends LlmTaskGoal<EatItemTaskRecord> {

    private int eatTicks;
    private int eatDuration;
    private float healed;
    private String doneReason = "done";

    public EatItemTaskGoal(AnimusEntity entity) {
        super(entity, EatItemTaskRecord.TOOL_NAME, EatItemTaskRecord.class);
    }

    @Override
    protected void onStart(EatItemTaskRecord r) {
        this.eatTicks = 0;
        this.healed = 0.0f;
        if (!entity.ensureInInventory(r.item)) {
            fail("no " + r.label + " in inventory or hands to eat");
            return;
        }
        if (!Eating.isEdible(r.item)) {
            fail(r.label + " is not edible");
            return;
        }
        if (entity.getHealth() >= entity.getMaxHealth()) {
            fail("already at full HP (" + hp() + ") — keeping the " + r.label);
            return;
        }
        this.eatDuration = Eating.durationTicks(r.item);
        // Stay RUNNING and chew it down over onTick.
    }

    @Override
    protected void onTick(EatItemTaskRecord r) {
        eatTicks++;
        // Chewing feedback partway through, on an interval (skip tick 0).
        if (eatTicks % Eating.EMIT_INTERVAL == 0) {
            Eating.emitChewing(entity, r.item);
        }
        if (eatTicks >= eatDuration) {
            finishEating(r);
        }
    }

    private void finishEating(EatItemTaskRecord r) {
        healed = Eating.finishEat(entity, r.item);
        if (healed < 0.0f) {
            healed = 0.0f;
            fail("the " + r.label + " was gone before I finished eating");
            return;
        }
        doneReason = "ate " + r.label + " — HP " + hp()
                + (healed > 0.0f ? " (+" + fmt(healed) + ")" : "");
        currentRecord.setState(TaskState.SUCCESS);
    }

    private void fail(String reason) {
        doneReason = reason;
        currentRecord.setState(TaskState.FAILED);
    }

    private String hp() {
        return fmt(entity.getHealth()) + "/" + fmt(entity.getMaxHealth());
    }

    private static String fmt(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v);
    }

    @Override
    protected TaskResult buildResult(EatItemTaskRecord r, TaskState finalState) {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("hp", entity.getHealth());
        data.put("max_hp", entity.getMaxHealth());
        if (finalState == TaskState.SUCCESS) {
            data.put("healed", healed);
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
