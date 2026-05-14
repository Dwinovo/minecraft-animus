package com.dwinovo.animus.anim.api;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.anim.runtime.Animator;
import net.minecraft.resources.Identifier;

/**
 * Marker interface for entities driven by the Animus animation pipeline.
 * Implementing this lets the entity renderer find the per-entity
 * {@link Animator}, the current model identifier, and the current task name
 * (Molang {@code query.task}).
 *
 * <p>The per-frame {@link com.dwinovo.animus.anim.runtime.AnimContext} is
 * built by the renderer at extract time rather than provided by the entity —
 * the entity only owns the animator state, the model selection, and the
 * task name.
 */
public interface AnimusAnimated {

    /**
     * Fallback model used when an implementation doesn't override
     * {@link #getModelKey()} or when its stored value fails to parse.
     * Matches the mod-shipped default.
     */
    Identifier DEFAULT_MODEL_KEY = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "hachiware");

    /** Per-entity animator. Holds one {@code ControllerInstance} per registered controller. */
    Animator getAnimator();

    /**
     * Identifier looked up against
     * {@link com.dwinovo.animus.anim.api.ModelLibrary},
     * {@link com.dwinovo.animus.anim.api.RenderControllerLibrary}, and the
     * texture path. All other per-entity render keys (default loop
     * animation, render controller, texture) are derived from this one.
     */
    default Identifier getModelKey() {
        return DEFAULT_MODEL_KEY;
    }

    /**
     * High-level task identifier hashed into Molang {@code query.task} at
     * extract time so author-written {@code render_controllers.json}
     * expressions like {@code query.task == 'play_music'} compare against it.
     * Default {@code "none"} keeps entities without a Brain in the resting
     * state.
     */
    default String getCurrentTask() {
        return "none";
    }
}
