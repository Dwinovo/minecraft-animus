package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

/**
 * Reusable melee-attack state machine — the combat analog of
 * {@link BlockMiningProgress}. Given a target the entity is already within reach
 * of, it swings on the attack cooldown and applies the hit (damage + knockback +
 * enchantments, all via the vanilla {@code doHurtTarget}). The <em>approach</em>
 * is the caller's job (it drives a {@link com.dwinovo.animus.pathing.exec.Navigator}
 * toward the target); this engine only owns the in-reach swing loop, so both the
 * single-target {@code attack_target} and the intent-level {@code hunt} share it.
 */
public final class MeleeEngine {

    /** Ticks between swings (~1s, the vanilla default attack interval for most mobs). */
    private static final int ATTACK_INTERVAL = 20;

    private final AnimusEntity entity;
    private int cooldown = 0;

    public MeleeEngine(AnimusEntity entity) {
        this.entity = entity;
    }

    /**
     * Squared melee reach to {@code target}, mirroring vanilla
     * {@code Mob.getMeleeAttackRangeSqr}: {@code (width*2)² + target.width}.
     */
    public static double attackReachSqr(AnimusEntity entity, LivingEntity target) {
        double w = entity.getBbWidth() * 2.0;
        return w * w + target.getBbWidth();
    }

    /** Is {@code target} close enough to hit right now? */
    public boolean inReach(LivingEntity target) {
        return entity.distanceToSqr(target.getX(), target.getY(), target.getZ())
                <= attackReachSqr(entity, target);
    }

    /**
     * Advance one tick of engagement: face the target, and when in reach and off
     * cooldown, swing and land a hit. Returns {@code true} once the target is
     * dead or dying.
     */
    public boolean tick(LivingEntity target) {
        entity.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (cooldown > 0) {
            cooldown--;
        }
        if (cooldown <= 0 && inReach(target) && entity.level() instanceof ServerLevel level) {
            cooldown = ATTACK_INTERVAL;
            entity.swing(InteractionHand.MAIN_HAND);
            entity.doHurtTarget(level, target);
        }
        return target.isDeadOrDying();
    }

    /** Reset the cooldown (call when switching targets). */
    public void reset() {
        cooldown = 0;
    }
}
