package com.dwinovo.animus.anim.render;

import com.dwinovo.animus.anim.baked.BakedModel;
import com.dwinovo.animus.anim.molang.MolangContext;

/**
 * Programmatic post-processing pass that runs <em>after</em>
 * {@link com.dwinovo.animus.anim.runtime.PoseSampler} and <em>before</em>
 * {@link ModelRenderer}, mutating the pose buffer in place.
 *
 * <p>This hook is where the renderer overrides specific bones with values
 * derived from live entity state (head look-at, ear sway, tail wag, future
 * spring-bone secondary motion) rather than from the animation file. Working
 * on the flat {@code float[]} pose buffer instead of the {@code PoseStack}
 * avoids redundant matrix push/pop pairs and keeps the data layout
 * JNI-friendly for a future Rust port.
 *
 * <h2>Stages</h2>
 * Interceptors run in {@link Stage} order. Within a stage they run in
 * registration order. The stages are deliberate, not cosmetic — later stages
 * can read what earlier stages wrote, so a spring-bone solver in
 * {@link Stage#PHYSICS_SECONDARY} sees the head's look-at result from
 * {@link Stage#LOOK_AT}.
 *
 * <p>Interceptors completely override the affected bone slots — they do not
 * blend with the animation's contribution to those slots. Layered procedural
 * effects on the same bone should be implemented by registering multiple
 * interceptors and ordering them carefully.
 */
@FunctionalInterface
public interface BoneInterceptor {

    /** Pipeline stage; interceptors run in this order. */
    enum Stage {
        /** Look-at and IK overrides driven by external targets (head, eye direction). */
        LOOK_AT,
        /** Time-driven secondary motion: idle sway, ear flick, tail wag, spring bones. */
        PHYSICS_SECONDARY,
        /** Visibility / occlusion edits: hide bones based on emote / equipment / cull rules. */
        OCCLUSION;

        /** Cached values() to avoid per-frame allocation; treat as immutable. */
        public static final Stage[] VALUES = values();
    }

    void apply(BakedModel model, AnimusRenderState state, MolangContext ctx, float[] poseBuf);
}
