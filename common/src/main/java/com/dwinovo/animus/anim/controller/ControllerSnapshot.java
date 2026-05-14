package com.dwinovo.animus.anim.controller;

import com.dwinovo.animus.anim.runtime.AnimationChannel;
import org.jspecify.annotations.Nullable;

/**
 * Immutable per-frame snapshot of a {@link ControllerInstance}, captured at
 * extract time and consumed by the renderer at submit time.
 *
 * <p>Putting a snapshot rather than the live instance into the render-state
 * preserves the contract that submit is a pure function of render-state +
 * baked data — a second extract in the same frame
 * ({@code InventoryScreen.renderEntityInInventoryFollowsMouse}) snapshots
 * fresh values without disturbing what the world-render submit had captured.
 *
 * <h2>Three sample paths</h2>
 * <ol>
 *   <li>Idle — {@link #current} is {@code null}; the controller contributes
 *       nothing this frame.</li>
 *   <li>Crossfade — {@link #previous} and {@link #current} both non-null.
 *       Within-controller switch (e.g. main: idle → sit). Renderer samples
 *       both, lerps via {@code fadeStartNs / fadeDurationSec}.</li>
 *   <li>Stop-fade — {@link #fadingOut} is {@code true}, {@link #previous} is
 *       {@code null}. The controller is in the process of going silent
 *       (handler returned {@code null} or a triggered animation finished).
 *       Renderer samples {@link #current} but lerps the contribution toward
 *       whatever earlier controllers wrote into the pose buffer.</li>
 * </ol>
 *
 * <p>{@code previous != null} and {@code fadingOut} are mutually exclusive —
 * {@link ControllerInstance#enterStopFade} drops any pending crossfade.
 *
 * @param name             identity of the source controller (debug only)
 * @param blendMode        how the renderer should combine this controller's
 *                         contribution with the existing pose buffer values
 * @param current          channel that is currently authoritative for this
 *                         controller, or {@code null} when the controller is
 *                         contributing nothing this frame
 * @param previous         channel being faded out from during a within-controller
 *                         switch, or {@code null} when no switch is in progress
 * @param fadeStartNs      animation-clock time when the active fade began
 * @param fadeDurationSec  fade length in seconds; {@code 0} = no fade
 * @param fadingOut        {@code true} when the controller is fading toward
 *                         "no contribution" (will go idle when the fade ends)
 */
public record ControllerSnapshot(
        String name,
        BlendMode blendMode,
        @Nullable AnimationChannel current,
        @Nullable AnimationChannel previous,
        long fadeStartNs,
        float fadeDurationSec,
        boolean fadingOut
) {
    public boolean isIdle() {
        return current == null;
    }

    /** Returns whether a within-controller switch crossfade is in progress. */
    public boolean hasCrossfade() {
        return previous != null;
    }

    /** Returns whether this controller is fading toward no contribution. */
    public boolean isFadingOut() {
        return fadingOut;
    }
}
