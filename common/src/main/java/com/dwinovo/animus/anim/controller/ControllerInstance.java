package com.dwinovo.animus.anim.controller;

import com.dwinovo.animus.anim.baked.BakedAnimation;
import com.dwinovo.animus.anim.baked.LoopMode;
import com.dwinovo.animus.anim.render.AnimusRenderState;
import com.dwinovo.animus.anim.runtime.AnimationChannel;
import com.dwinovo.animus.anim.runtime.AnimContext;
import org.jspecify.annotations.Nullable;

/**
 * Per-entity mutable state for one {@link ControllerConfig}.
 *
 * <h2>What it tracks</h2>
 * <ul>
 *   <li>{@code currentAnim} — the channel currently authoritative for this controller</li>
 *   <li>{@code previousAnim} — the channel being faded <em>out from</em> during a
 *       within-controller switch (e.g. main: idle → sit). Both channels are
 *       sampled every frame and blended via {@link com.dwinovo.animus.anim.runtime.PoseMixer}.</li>
 *   <li>{@code fadingOut} flag — set when the controller stops contributing
 *       (handler returned {@code null} or a triggered animation finished).
 *       The current channel keeps sampling, but its contribution fades toward
 *       whatever earlier controllers wrote into the pose buffer. After the
 *       fade completes, {@code currentAnim} is cleared and the controller
 *       goes silent.</li>
 *   <li>{@code triggeredAnim} — a {@link #playOnce} override that wins over
 *       the handler until its baked duration elapses. GeckoLib's
 *       {@code triggeredAnim} mechanism, used by
 *       {@link com.dwinovo.animus.entity.AnimusEntity}'s synced action /
 *       reaction packets.</li>
 * </ul>
 *
 * <h2>Two kinds of fade, one timer</h2>
 * Within-controller switch and stop-fade share {@code fadeStartNs /
 * fadeDurationSec}. They never coexist — entering one cancels the other.
 *
 * <h2>Lifecycle per frame</h2>
 * {@link #tick} runs at extract time and bookkeeps fades. {@link #snapshot}
 * produces an immutable {@link ControllerSnapshot} for the renderer's submit
 * pass. Splitting tick from sample ensures the second extract for an
 * inventory preview cannot drift relative to the world-render submit.
 */
public final class ControllerInstance {

    private final ControllerConfig config;
    /**
     * Stable per-entity offset used as the {@code startTimeNs} for looping
     * animations we want phase-shifted across entities (so two pets created at
     * the same instant don't blink in lockstep). Set once via
     * {@link #setPhaseSeed} and reused for the lifetime of this instance.
     */
    private long phaseSeed = Long.MIN_VALUE;

    @Nullable private BakedAnimation currentAnim;
    private long currentStartNs;
    private boolean currentLooping;

    @Nullable private BakedAnimation previousAnim;
    private long previousStartNs;
    private boolean previousLooping;

    /** Animation-clock nanos when the active fade (switch OR stop-fade) began. */
    private long fadeStartNs;
    /** Length of the active fade in seconds; {@code 0} = no fade in progress. */
    private float fadeDurationSec;
    /**
     * {@code true} when the controller is fading out toward "no contribution"
     * (handler returned {@code null} or a trigger finished). Mutually exclusive
     * with {@code previousAnim != null} — entering one cancels the other.
     */
    private boolean fadingOut;

    @Nullable private BakedAnimation triggeredAnim;
    private long triggeredStartNs;

    public ControllerInstance(ControllerConfig config) {
        this.config = config;
    }

    public ControllerConfig config() {
        return config;
    }

    public String name() {
        return config.name();
    }

    /**
     * Latches the entity-stable phase seed used to anchor non-fading
     * looping animations. Subsequent calls are no-ops to keep the seed stable
     * for the lifetime of the instance.
     */
    public void setPhaseSeed(long phaseSeed) {
        if (this.phaseSeed != Long.MIN_VALUE) return;
        this.phaseSeed = phaseSeed;
    }

    /**
     * Trigger a one-shot animation that wins over the {@link ControllerHandler}
     * until its baked duration elapses. {@link LoopMode#HOLD_ON_LAST_FRAME}
     * animations stay until the next {@code playOnce} or {@link #clearTrigger}.
     *
     * <p>Re-triggering with a different animation restarts the timer.
     * Re-triggering with the same animation is a no-op.
     */
    public void playOnce(BakedAnimation animation, long nowNs) {
        if (animation == null) return;
        if (animation == this.triggeredAnim) return;
        this.triggeredAnim = animation;
        this.triggeredStartNs = nowNs;
    }

    /** Clears any active triggered animation, returning control to the handler. */
    public void clearTrigger() {
        this.triggeredAnim = null;
    }

    /**
     * Drops everything: any current/previous channel state and any active
     * trigger. Used when a stale-bake reload invalidates the animation
     * objects this controller was holding references to.
     */
    public void resetAll() {
        this.currentAnim = null;
        this.previousAnim = null;
        this.triggeredAnim = null;
        this.fadeStartNs = 0L;
        this.fadeDurationSec = 0f;
        this.fadingOut = false;
    }

    /**
     * Drops state if any held animation belongs to a stale bake generation.
     * Stamp {@code 0} on either side is treated as "unset" so test ctors
     * (which omit the stamp) stay unaffected.
     */
    public boolean clearStale(long currentStamp) {
        if (currentStamp == 0L) return false;
        if (isStale(currentAnim, currentStamp) || isStale(previousAnim, currentStamp) || isStale(triggeredAnim, currentStamp)) {
            resetAll();
            return true;
        }
        return false;
    }

    private static boolean isStale(BakedAnimation anim, long stamp) {
        return anim != null && anim.bakeStamp != 0L && anim.bakeStamp != stamp;
    }

    /**
     * Advance this controller's state for the upcoming render pass.
     *
     * <ol>
     *   <li>Reap finished within-controller crossfades and stop-fades.</li>
     *   <li>If a triggered animation is still active and unfinished, it
     *       remains authoritative. If it just finished, enter stop-fade.</li>
     *   <li>Otherwise consult the {@link ControllerHandler}. Switching to a
     *       new animation starts a crossfade. The handler returning
     *       {@code null} starts a stop-fade (if a current was active).</li>
     * </ol>
     */
    public void tick(AnimusRenderState state, AnimContext ctx, long nowNs) {
        // Reap finished within-controller crossfade (previous → current).
        if (previousAnim != null && fadeFinished(nowNs)) {
            previousAnim = null;
            fadeDurationSec = 0f;
        }
        // Reap finished stop-fade — controller goes silent for real.
        if (fadingOut && fadeFinished(nowNs)) {
            currentAnim = null;
            fadingOut = false;
            fadeDurationSec = 0f;
        }

        // Triggered animation overrides the handler until it finishes.
        if (triggeredAnim != null) {
            if (isAnimationFinished(triggeredAnim, triggeredStartNs, nowNs)) {
                // Trigger ended — let it fade out from its last pose.
                triggeredAnim = null;
                if (currentAnim != null) {
                    enterStopFade(nowNs);
                }
            } else {
                // Still active — switch to triggered without fade-in.
                switchTo(triggeredAnim, triggeredStartNs, /*looping=*/false, /*allowFade=*/false, nowNs);
                fadingOut = false;
                return;
            }
        }

        BakedAnimation wanted = config.handler().handle(state, ctx);
        if (wanted == null) {
            // Handler says "nothing this frame". If we were playing something,
            // start a stop-fade so the bone(s) the controller was animating
            // can blend smoothly back to whatever earlier controllers wrote.
            if (currentAnim != null && !fadingOut) {
                enterStopFade(nowNs);
            }
            return;
        }

        boolean looping = wanted.loopMode == LoopMode.LOOP;
        long startNs = looping && phaseSeed != Long.MIN_VALUE ? phaseSeed : nowNs;
        switchTo(wanted, startNs, looping, /*allowFade=*/true, nowNs);
        fadingOut = false; // wanted came in, cancel any pending stop-fade
    }

    /**
     * Switches the authoritative animation, starting a crossfade if
     * {@code allowFade} is true and the controller has a configured transition.
     * Re-selecting the same animation is idempotent — neither the start time
     * nor the looping flag is disturbed.
     *
     * @param animation animation to play
     * @param startNs   when this animation's local time should treat as t=0
     *                  (for looping anims this is the entity's phase seed so
     *                  multiple pets blink/breathe out of sync)
     * @param looping   whether {@code anim_time} should wrap on duration
     * @param allowFade whether a crossfade should be opened when replacing
     *                  an existing channel
     * @param nowNs     animation-clock time used for the fade timer; must be
     *                  the actual current frame time, never the animation's
     *                  start time (those diverge for looping anims)
     */
    private void switchTo(BakedAnimation animation, long startNs, boolean looping, boolean allowFade, long nowNs) {
        if (animation == currentAnim && currentLooping == looping && !fadingOut) {
            return; // already playing this; stable
        }
        if (allowFade && currentAnim != null && config.transitionSec() > 0f) {
            previousAnim = currentAnim;
            previousStartNs = currentStartNs;
            previousLooping = currentLooping;
            fadeStartNs = nowNs;
            fadeDurationSec = config.transitionSec();
        } else {
            previousAnim = null;
            fadeDurationSec = 0f;
        }
        currentAnim = animation;
        currentStartNs = startNs;
        currentLooping = looping;
        fadingOut = false;
    }

    /**
     * Enter the stop-fade state: the current animation keeps being sampled,
     * but its contribution will linearly fade toward "whatever earlier
     * controllers wrote into the pose buffer" over the controller's
     * {@code transitionSec}.
     *
     * <p>If the controller has no transition configured, the stop is a clean
     * cut (currentAnim cleared immediately).
     */
    private void enterStopFade(long nowNs) {
        if (config.transitionSec() <= 0f) {
            currentAnim = null;
            previousAnim = null;
            fadeDurationSec = 0f;
            fadingOut = false;
            return;
        }
        previousAnim = null; // any pending switch is dropped
        fadeStartNs = nowNs;
        fadeDurationSec = config.transitionSec();
        fadingOut = true;
    }

    private boolean fadeFinished(long nowNs) {
        if (fadeDurationSec <= 0f) return true;
        return nowNs - fadeStartNs >= (long) (fadeDurationSec * 1_000_000_000L);
    }

    /** Returns {@code true} if {@code anim} has reached the end of its baked duration. */
    private static boolean isAnimationFinished(BakedAnimation anim, long startNs, long nowNs) {
        if (anim == null) return true;
        if (anim.loopMode == LoopMode.HOLD_ON_LAST_FRAME) return false;
        if (anim.loopMode == LoopMode.LOOP) return false;
        if (anim.durationSec <= 0f) return true;
        long elapsed = nowNs - startNs;
        if (elapsed < 0L) return false;
        return elapsed >= (long) (anim.durationSec * 1_000_000_000L);
    }

    /** Build the immutable snapshot the renderer will sample at submit time. */
    public ControllerSnapshot snapshot() {
        AnimationChannel currentChannel = currentAnim == null
                ? null
                : new AnimationChannel(currentAnim, currentStartNs, currentLooping);
        AnimationChannel previousChannel = previousAnim == null
                ? null
                : new AnimationChannel(previousAnim, previousStartNs, previousLooping);
        return new ControllerSnapshot(
                config.name(),
                config.blendMode(),
                currentChannel,
                previousChannel,
                fadeStartNs,
                fadeDurationSec,
                fadingOut);
    }
}
