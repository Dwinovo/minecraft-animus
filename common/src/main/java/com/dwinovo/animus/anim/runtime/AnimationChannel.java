package com.dwinovo.animus.anim.runtime;

import com.dwinovo.animus.anim.baked.BakedAnimation;

/**
 * A single playing animation. Immutable — to start a new animation, replace
 * the whole record rather than mutating fields.
 *
 * <p>This immutability is the core fix for double-extract timing drift: there
 * is no "lastAnimatableAge" field that gets advanced on every render-state
 * extract. The current pose is always derived as a pure function of
 * {@code (animation, startTimeNs, nowNs)} on the pause-aware animation clock,
 * so calling
 * {@link com.dwinovo.animus.anim.runtime.PoseSampler} twice in the same
 * frame produces identical results — GUI preview and world render naturally
 * agree.
 *
 * <p>Fade-in / fade-out timing lives on
 * {@link com.dwinovo.animus.anim.controller.ControllerInstance} rather than
 * here: the channel knows what's playing, the controller knows what's fading.
 *
 * @param animation   the baked animation being played
 * @param startTimeNs animation-clock time captured at trigger
 * @param looping     whether to wrap {@code (now - start) % duration}
 */
public record AnimationChannel(
        BakedAnimation animation,
        long startTimeNs,
        boolean looping
) {
}
