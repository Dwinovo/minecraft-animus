package com.dwinovo.animus.anim.baked;

/**
 * Immutable, render-ready animation. Shared across all entities playing the
 * same animation — never mutated after baking.
 *
 * <p>Channels are flat-arrayed in iteration order. Each {@link BakedBoneChannel}
 * carries the index of the bone it targets in the model's bone array, so the
 * sampler can write directly into a pose buffer indexed by that same array.
 *
 * <p>The mapping from a {@link BakedAnimation} to a specific {@link BakedModel}
 * happens at sample time via the bone-index field on each channel. An
 * animation only references bone names that exist in the model — channels for
 * unknown names are dropped at bake time (with a warning).
 *
 * <p>{@link #loopMode} preserves the Bedrock {@code loop} field's three-state
 * semantic ({@code false} / {@code true} / {@code "hold_on_last_frame"}) all
 * the way to the runtime, so the sampler and the animator's
 * {@code isFinished} check both make the right decision without losing the
 * animator's authoring intent.
 *
 * <p>{@link #bakeStamp} matches the stamp on the {@link BakedModel} this
 * animation was baked against. After a resource reload the stamp on
 * {@link BakeStamp} is bumped and fresh objects are produced; any cached
 * reference compares unequal and can be detected as stale before its
 * out-of-date bone indices reach the sampler.
 */
public final class BakedAnimation {

    public final String name;
    /** Total animation length in seconds. */
    public final float durationSec;
    /** Bedrock {@code loop}-field semantic preserved as a typed enum. */
    public final LoopMode loopMode;

    public final BakedBoneChannel[] channels;

    /** {@link BakeStamp} value at the moment this animation was baked. */
    public final long bakeStamp;

    /** Test-only convenience: maps {@code looping=true} → LOOP, {@code false} → PLAY_ONCE. */
    public BakedAnimation(String name, float durationSec, boolean looping, BakedBoneChannel[] channels) {
        this(name, durationSec, looping ? LoopMode.LOOP : LoopMode.PLAY_ONCE, channels, 0L);
    }

    /** Test-only convenience with bake stamp. */
    public BakedAnimation(String name, float durationSec, boolean looping,
                          BakedBoneChannel[] channels, long bakeStamp) {
        this(name, durationSec, looping ? LoopMode.LOOP : LoopMode.PLAY_ONCE, channels, bakeStamp);
    }

    /** Stamp-less LoopMode ctor for non-resource paths. */
    public BakedAnimation(String name, float durationSec, LoopMode loopMode, BakedBoneChannel[] channels) {
        this(name, durationSec, loopMode, channels, 0L);
    }

    /** Canonical ctor — used by the bake pipeline. */
    public BakedAnimation(String name, float durationSec, LoopMode loopMode,
                          BakedBoneChannel[] channels, long bakeStamp) {
        this.name = name;
        this.durationSec = durationSec;
        this.loopMode = loopMode == null ? LoopMode.PLAY_ONCE : loopMode;
        this.channels = channels;
        this.bakeStamp = bakeStamp;
    }
}
