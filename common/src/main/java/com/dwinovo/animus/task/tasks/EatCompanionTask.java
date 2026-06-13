package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.PlayerInv;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code eat_item} on the player body: a real, timed eat — chewing particles +
 * sound over the food's consume duration, heal + effects apply only on
 * completion (interrupting wastes nothing). No hunger system; the heal lands
 * directly, scaled by nutrition. Player-body twin of EatItemTaskGoal (the chew
 * mechanics are inlined here over the player Inventory; the Mob's AutoEater
 * still uses the shared {@code Eating} helper).
 */
public final class EatCompanionTask implements CompanionTask {

    private final AnimusPlayer player;
    private final EatItemTaskRecord r;
    private int eatTicks;
    private int eatDuration;
    private float healed;
    private String doneReason = "done";

    public EatCompanionTask(AnimusPlayer player, EatItemTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        eatTicks = 0;
        healed = 0.0f;
        if (PlayerInv.count(player.getInventory(), r.item) <= 0) {
            fail("no " + r.label + " in inventory to eat");
            return;
        }
        if (!Eating.isEdible(r.item)) {
            fail(r.label + " is not edible");
            return;
        }
        if (player.getHealth() >= player.getMaxHealth()) {
            fail("already at full HP (" + hp() + ") — keeping the " + r.label);
            return;
        }
        eatDuration = Eating.durationTicks(r.item);
    }

    @Override
    public TaskState tick() {
        if (r.getState() != TaskState.RUNNING) return r.getState();
        eatTicks++;
        if (eatTicks % Eating.EMIT_INTERVAL == 0) {
            emitChewing();
        }
        if (eatTicks >= eatDuration) {
            finishEating();
        }
        return r.getState();
    }

    private void emitChewing() {
        if (!(player.level() instanceof ServerLevel sl)) return;
        Vec3 look = player.getLookAngle();
        sl.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, r.item),
                player.getX() + look.x * 0.35, player.getEyeY() - 0.2, player.getZ() + look.z * 0.35,
                4, 0.1, 0.1, 0.1, 0.03);
        float pitch = 0.9f + player.getRandom().nextFloat() * 0.2f;
        sl.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.6f, pitch);
    }

    private void finishEating() {
        if (PlayerInv.count(player.getInventory(), r.item) <= 0) {
            fail("the " + r.label + " was gone before I finished eating");
            return;
        }
        ItemStack one = new ItemStack(r.item);
        float before = player.getHealth();
        FoodProperties food = one.get(DataComponents.FOOD);
        if (food != null) player.heal(food.nutrition());
        Consumable consumable = one.get(DataComponents.CONSUMABLE);
        if (consumable != null && player.level() instanceof ServerLevel sl) {
            ItemStack leftover = consumable.onConsume(sl, player, one);
            if (!leftover.isEmpty()) PlayerInv.add(player.getInventory(), leftover);
        }
        PlayerInv.remove(player.getInventory(), r.item, 1);
        healed = player.getHealth() - before;
        doneReason = "ate " + r.label + " — HP " + hp() + (healed > 0.0f ? " (+" + fmt(healed) + ")" : "");
        r.setState(TaskState.SUCCESS);
    }

    private void fail(String reason) {
        doneReason = reason;
        r.setState(TaskState.FAILED);
    }

    private String hp() {
        return fmt(player.getHealth()) + "/" + fmt(player.getMaxHealth());
    }

    private static String fmt(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v);
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("hp", player.getHealth());
        data.put("max_hp", player.getMaxHealth());
        if (finalState == TaskState.SUCCESS) {
            data.put("healed", healed);
            return TaskResult.ok(doneReason, data);
        }
        return switch (finalState) {
            case TIMEOUT -> TaskResult.timeout("couldn't finish eating " + r.label);
            case CANCELLED -> TaskResult.cancelled("eating " + r.label + " interrupted — no effect");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
