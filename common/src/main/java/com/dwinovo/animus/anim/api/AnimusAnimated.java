package com.dwinovo.animus.anim.api;

import com.dwinovo.animus.anim.runtime.Animator;

/**
 * Marker interface for entities driven by the Animus animation pipeline.
 * Implementing this lets the entity renderer find the per-entity
 * {@link Animator} and read the current high-level task identifier (for
 * Molang {@code entity.task} expressions in {@code render_controllers.json}).
 *
 * <p>The per-frame {@link com.dwinovo.animus.anim.runtime.AnimContext} is
 * built by the renderer at extract time rather than provided by the entity —
 * the entity only owns the animator state and the task name.
 */
public interface AnimusAnimated {

    /** Per-entity animator. Holds one {@code ControllerInstance} per registered controller. */
    Animator getAnimator();

    /**
     * Identifier of the high-level task the entity is currently performing.
     * Hashed into {@code entity.task} at extract time so {@code render_controllers.json}
     * expressions like {@code entity.task == 'play_music'} compare against it.
     *
     * <p>Default returns {@code "none"} — entities that haven't wired their
     * LLM-driven task plumbing yet stay in the resting state. When the Brain /
     * ToolCall plumbing lands, this should return the live task name.
     */
    default String getCurrentTask() {
        return "none";
    }
}
