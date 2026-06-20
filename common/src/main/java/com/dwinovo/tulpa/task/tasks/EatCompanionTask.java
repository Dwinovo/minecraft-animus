package com.dwinovo.tulpa.task.tasks;

import com.dwinovo.tulpa.entity.TulpaPlayer;
import com.dwinovo.tulpa.pathing.exec.Interaction;
import com.dwinovo.tulpa.task.CompanionTask;
import com.dwinovo.tulpa.task.PlayerInv;
import com.dwinovo.tulpa.task.TaskResult;
import com.dwinovo.tulpa.task.TaskState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code eat_item} on the player body — a thin wrapper over the native held use. Hold the food and
 * run {@link Interaction#useInAir} on a {@code hold()} timing: that fires {@code gameMode.useItem},
 * and the body's own {@code aiStep} (ticked via {@code doTick}) drives the real eat to completion —
 * chewing animation/particles/sound, hunger + saturation + consume-effects on finish, modded foods,
 * and any mod events. No hand-rolled chew loop or fake direct heal: the companion is a ServerPlayer
 * with a live {@code FoodData}, so eating works exactly as it does for a real player (full hunger →
 * it simply won't eat, which we detect and report).
 */
public final class EatCompanionTask implements CompanionTask {

    private final TulpaPlayer player;
    private final EatItemTaskRecord r;

    private Interaction eat;
    private int beforeCount;
    private float beforeHp;
    private int beforeFood;
    private String doneReason = "done";

    public EatCompanionTask(TulpaPlayer player, EatItemTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        beforeCount = PlayerInv.count(player.getInventory(), r.item);
        if (beforeCount <= 0) {
            fail("no " + r.label + " in inventory to eat");
            return;
        }
        // Native "is this consumable?" — covers food, potions, milk, and modded consumables alike.
        if (new ItemStack(r.item).get(DataComponents.FOOD) == null) {
            fail(r.label + " can't be eaten or drunk");
            return;
        }
        beforeHp = player.getHealth();
        beforeFood = player.getFoodData().getFoodLevel();
        // Equip the food, then start a native held use. The use() call decides whether eating begins
        // (e.g. full hunger on non-always-eat food won't start) — we read the outcome on completion.
        player.holdInHand(PlayerInv.findSlot(player.getInventory(), r.item));
        eat = Interaction.useInAir(player, InteractionHand.MAIN_HAND, Interaction.Timing.hold());
    }

    @Override
    public TaskState tick() {
        if (r.getState() != TaskState.RUNNING) {
            return r.getState();
        }
        if (eat == null) {
            return TaskState.FAILED;   // start() already failed
        }
        return switch (eat.tick()) {
            case DONE -> {
                finish();
                yield r.getState();
            }
            case FAILED -> {
                fail("couldn't eat " + r.label + ": " + eat.failReason());
                yield TaskState.FAILED;
            }
            case RUNNING -> TaskState.RUNNING;
        };
    }

    /** The held use finished. If an item was actually consumed, report the hunger/HP it restored;
     *  otherwise the body declined to eat (typically already full) — say so rather than claim success. */
    private void finish() {
        int now = PlayerInv.count(player.getInventory(), r.item);
        if (now >= beforeCount) {
            fail("didn't eat " + r.label + " — already full (hunger " + beforeFood + "/20). Kept it.");
            return;
        }
        int foodGain = player.getFoodData().getFoodLevel() - beforeFood;
        float healed = player.getHealth() - beforeHp;
        doneReason = "ate " + r.label + " — hunger " + player.getFoodData().getFoodLevel() + "/20"
                + (foodGain > 0 ? " (+" + foodGain + ")" : "")
                + (healed > 0.0f ? ", HP " + fmt(player.getHealth()) + " (+" + fmt(healed) + ")" : "");
        r.setState(TaskState.SUCCESS);
    }

    private void fail(String reason) {
        doneReason = reason;
        r.setState(TaskState.FAILED);
    }

    private static String fmt(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v);
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (eat != null) {
            eat.stop();
        }
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("hp", player.getHealth());
        data.put("hunger", player.getFoodData().getFoodLevel());
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case TIMEOUT -> TaskResult.timeout("couldn't finish eating " + r.label);
            case CANCELLED -> TaskResult.cancelled("eating " + r.label + " interrupted — no effect");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
