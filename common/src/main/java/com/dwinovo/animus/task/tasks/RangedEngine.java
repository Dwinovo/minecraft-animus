package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;

/**
 * Reusable ranged-attack state machine — the bow counterpart of
 * {@link MeleeEngine}. Given a target the caller has driven within bow range and
 * line of sight, it draws and looses an {@link Arrow} on the shot cooldown,
 * consuming one arrow from the inventory per shot. The <em>approach / kiting</em>
 * is the caller's job (it drives a {@link com.dwinovo.animus.pathing.exec.Navigator}
 * to within range + LOS); this engine only owns the aim-and-fire loop.
 *
 * <p>Requires a {@link BowItem} in the main hand and at least one
 * {@link ArrowItem} stack — {@link #hasBow()} / {@link #hasArrows()} let the
 * caller gate up front with an actionable failure (the model's instruction:
 * equip a bow, carry arrows).
 */
public final class RangedEngine {

    /** Ticks between shots (~1.5s — bow draw + recovery). */
    private static final int SHOOT_INTERVAL = 30;
    /**
     * Launch velocity. MUST stay 1.6 — the {@code dy + horiz * 0.2}
     * gravity-compensation lob below is the vanilla skeleton formula, and that
     * 0.2 arc constant is calibrated for exactly this speed. Faster arrows fly
     * flatter, so a higher velocity makes the same lob overshoot wildly (the
     * "works but never hits — sails over the target" bug).
     */
    private static final float ARROW_VELOCITY = 1.6F;
    /** Small spread so shots aren't pixel-perfect. */
    private static final float INACCURACY = 1.0F;

    private final AnimusEntity entity;
    private int cooldown = 0;

    public RangedEngine(AnimusEntity entity) {
        this.entity = entity;
    }

    public boolean hasBow() {
        return entity.getMainHandItem().getItem() instanceof BowItem;
    }

    public boolean hasArrows() {
        return findArrow() != null;
    }

    /**
     * Advance one tick of ranged engagement: face the target, and when off
     * cooldown fire an arrow (consuming one). Returns {@code true} once the
     * target is gone (dead or removed — e.g. an end crystal destroyed).
     */
    public boolean tick(Entity target) {
        entity.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (cooldown > 0) {
            cooldown--;
        } else if (entity.level() instanceof ServerLevel level) {
            ItemStack arrowStack = findArrow();
            if (arrowStack != null) {
                fireArrow(level, target, arrowStack);
                arrowStack.shrink(1);
                entity.getInventory().setChanged();
                cooldown = SHOOT_INTERVAL;
            }
        }
        return isDown(target);
    }

    public void reset() {
        cooldown = 0;
    }

    private void fireArrow(ServerLevel level, Entity target, ItemStack arrowStack) {
        ItemStack bow = entity.getMainHandItem();
        ArrowItem arrowItem = (ArrowItem) arrowStack.getItem();
        AbstractArrow arrow = arrowItem.createArrow(level, arrowStack.copyWithCount(1), entity, bow);

        // Aim at the target's mid-height, lobbing slightly to counter drop.
        double dx = target.getX() - entity.getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - entity.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horiz * 0.2, dz, ARROW_VELOCITY, INACCURACY);

        entity.playSound(SoundEvents.ARROW_SHOOT, 1.0F,
                1.0F / (entity.getRandom().nextFloat() * 0.4F + 0.8F));
        level.addFreshEntity(arrow);
    }

    private static boolean isDown(Entity target) {
        return target == null || target.isRemoved()
                || (target instanceof net.minecraft.world.entity.LivingEntity le && le.isDeadOrDying());
    }

    /** First arrow stack in the inventory, or null. */
    private ItemStack findArrow() {
        var inv = entity.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof ArrowItem) {
                return s;
            }
        }
        return null;
    }
}
