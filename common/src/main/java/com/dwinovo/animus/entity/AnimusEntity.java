package com.dwinovo.animus.entity;

import com.dwinovo.animus.anim.api.AnimusAnimated;
import com.dwinovo.animus.anim.runtime.Animator;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * Single LLM-driven entity at the heart of the mod. This stage of the project
 * only wires up the rendering pipeline — the Brain / ToolCall / LLM client
 * integration arrives in later plans. For now {@link #registerGoals()} stays
 * empty and the entity drifts in place; the renderer still drives the
 * idle / walk animations off the vanilla walk-animation speed.
 *
 * <p>The animator is allocated lazily on first client-side access so the
 * server doesn't pay for animation state that has no consumer there.
 */
public class AnimusEntity extends PathfinderMob implements AnimusAnimated {

    private Animator animator;

    public AnimusEntity(EntityType<? extends AnimusEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    @Override
    public Animator getAnimator() {
        if (animator == null) animator = new Animator();
        return animator;
    }

    @Override
    public String getCurrentTask() {
        // Stub for Phase 1 — wired up once the Brain / ToolCall plumbing lands.
        return "none";
    }
}
