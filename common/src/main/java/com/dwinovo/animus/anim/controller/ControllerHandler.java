package com.dwinovo.animus.anim.controller;

import com.dwinovo.animus.anim.baked.BakedAnimation;
import com.dwinovo.animus.anim.render.AnimusRenderState;
import com.dwinovo.animus.anim.runtime.AnimContext;
import org.jspecify.annotations.Nullable;

/**
 * Pure function from gameplay state to "what should this controller play
 * right now". Called every render-state extract, must be idempotent —
 * returning the same animation in two consecutive frames is a no-op (the
 * controller short-circuits on equality).
 *
 * <p>Returning {@code null} signals "this controller contributes nothing this
 * frame". The pose buffer is left untouched at this controller's stage —
 * later controllers and interceptors continue normally.
 *
 * <p>This is the GeckoLib {@code AnimationStateHandler} pattern, narrowed to
 * the data this codebase actually has: there's no {@code RawAnimation} stage
 * chain, no {@code PlayState} tri-state — just "play this animation, or
 * nothing".
 */
@FunctionalInterface
public interface ControllerHandler {
    /**
     * @param state per-frame render-state snapshot
     * @param ctx   gameplay state snapshot (mode / locomotion / job ...)
     * @return animation to play, or {@code null} for "no contribution"
     */
    @Nullable BakedAnimation handle(AnimusRenderState state, AnimContext ctx);

    /**
     * Handler for controllers that only run on external triggers — the state
     * machine never picks an animation for them. Used by the {@code "action"}
     * and {@code "reaction"} controllers, which receive their animations via
     * {@link com.dwinovo.animus.anim.runtime.Animator#playOnce}.
     */
    static @Nullable BakedAnimation neverPlay(AnimusRenderState state, AnimContext ctx) {
        return null;
    }
}
