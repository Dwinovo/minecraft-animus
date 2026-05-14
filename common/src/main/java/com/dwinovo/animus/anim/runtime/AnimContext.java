package com.dwinovo.animus.anim.runtime;

/**
 * Per-frame gameplay-state snapshot consumed by {@link
 * com.dwinovo.animus.anim.controller.ControllerHandler}. Currently empty —
 * future LLM-driven phases will add fields (mood, current action, intent,
 * locomotion bucket, ...) here without touching the controller plumbing.
 *
 * <p>Constructed at render-extract time by the renderer rather than provided
 * by the entity, so an entity that hasn't decided its AnimContext yet still
 * renders correctly with {@link #empty()}.
 */
public record AnimContext() {

    private static final AnimContext EMPTY = new AnimContext();

    public static AnimContext empty() {
        return EMPTY;
    }
}
