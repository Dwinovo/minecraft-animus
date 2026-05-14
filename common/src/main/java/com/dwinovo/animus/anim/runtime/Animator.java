package com.dwinovo.animus.anim.runtime;

import com.dwinovo.animus.anim.baked.BakedAnimation;
import com.dwinovo.animus.anim.controller.ControllerConfig;
import com.dwinovo.animus.anim.controller.ControllerInstance;
import com.dwinovo.animus.anim.controller.ControllerSnapshot;
import com.dwinovo.animus.anim.render.AnimusRenderState;
import com.dwinovo.animus.anim.runtime.AnimContext;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Per-entity animation state. Holds one {@link ControllerInstance} per
 * {@link ControllerConfig} the renderer registered.
 *
 * <h2>Controller iteration</h2>
 * Controllers run in registration order (the renderer's static config list).
 * Each controller writes into the shared pose buffer at submit time, so a
 * controller registered later sees and may override / blend onto values
 * written by earlier ones — this is the pillar of the GeckoLib-style pipeline
 * model: the registration order <em>is</em> the priority.
 *
 * <h2>Why initialisation is lazy</h2>
 * {@link com.dwinovo.animus.entity.AnimusEntity#getAnimator()} is called
 * eagerly on both client and server, but the controller config list lives on
 * the renderer (client only). The first {@link #ensureInitialised} call from
 * the renderer materialises the per-entity controller instances; until then
 * the animator is dormant. Server-side animators stay dormant for their
 * entire lifetime — they cost only a single empty-array {@code List}.
 *
 * <h2>One-shot triggers</h2>
 * {@link #playOnce} dispatches an externally-driven one-shot animation to the
 * named controller (typically {@code "action"} or {@code "reaction"}). The
 * triggered animation overrides the controller's state handler until its
 * baked duration elapses, then the handler resumes. Mirrors GeckoLib's
 * {@code triggerAnimation}.
 */
public final class Animator {

    /** Default crossfade for the main state-driven controller. */
    public static final float DEFAULT_BASE_TRANSITION_SEC = 0.16f;

    /**
     * Stable per-entity offset latched by {@link #setPhaseSeed} and propagated
     * to controller instances. {@link Long#MIN_VALUE} = unset.
     */
    private long phaseSeed = Long.MIN_VALUE;
    private List<ControllerInstance> controllers = List.of();

    public Animator() {
    }

    /**
     * Latches the entity-stable phase seed used to anchor non-fading
     * looping animations across two entities (so two pets created at the same
     * instant don't blink in lockstep). Subsequent calls are no-ops to keep
     * the seed stable for the lifetime of the animator.
     *
     * <p>The seed value is {@code nowNs - uniqueness * 1ms} so
     * that the elapsed time at frame {@code t} is uniformly distributed
     * across [0, ~10s] for a 16-bit uniqueness input.
     */
    public void setPhaseSeed(long uniquenessSeed, long nowNs) {
        if (phaseSeed != Long.MIN_VALUE) return;
        long offsetMs = Math.floorMod(uniquenessSeed, 10_000L);
        phaseSeed = nowNs - offsetMs * 1_000_000L;
        for (ControllerInstance c : controllers) c.setPhaseSeed(phaseSeed);
    }

    /**
     * Materialises one {@link ControllerInstance} per supplied
     * {@link ControllerConfig}, but only on the first call. Re-invoking with
     * the same configs (renderer is a singleton, configs are static) is a
     * no-op.
     *
     * <p>The renderer calls this before {@link #tick} so the very first
     * extract on a new entity gets a fully-built controller list.
     */
    public void ensureInitialised(List<ControllerConfig> configs) {
        if (!controllers.isEmpty()) return;
        if (configs == null || configs.isEmpty()) return;
        ControllerInstance[] instances = new ControllerInstance[configs.size()];
        for (int i = 0; i < configs.size(); i++) {
            instances[i] = new ControllerInstance(configs.get(i));
            if (phaseSeed != Long.MIN_VALUE) instances[i].setPhaseSeed(phaseSeed);
        }
        controllers = List.of(instances);
    }

    /**
     * Drops controllers whose held animations belong to a stale bake
     * generation (post-resource-reload). Bake stamp {@code 0} on either side
     * is treated as "unset" so simple test ctors stay unaffected.
     */
    public void clearStale(long currentStamp) {
        if (currentStamp == 0L) return;
        for (ControllerInstance c : controllers) c.clearStale(currentStamp);
    }

    /** Advance every controller's state for the upcoming render pass. */
    public void tick(AnimusRenderState state, AnimContext ctx, long nowNs) {
        for (ControllerInstance c : controllers) c.tick(state, ctx, nowNs);
    }

    /**
     * Build an immutable snapshot array — one entry per controller, in
     * registration order. The renderer hands this to its submit pass to
     * sample the pose buffer.
     */
    public ControllerSnapshot[] snapshot() {
        if (controllers.isEmpty()) return new ControllerSnapshot[0];
        ControllerSnapshot[] out = new ControllerSnapshot[controllers.size()];
        for (int i = 0; i < controllers.size(); i++) out[i] = controllers.get(i).snapshot();
        return out;
    }

    /**
     * Trigger a one-shot animation on the named controller. The trigger
     * overrides the controller's state handler until {@code animation}'s
     * baked duration elapses (or, for {@code HOLD_ON_LAST_FRAME}, until the
     * trigger is replaced or cleared).
     *
     * <p>Used by {@link com.dwinovo.animus.entity.AnimusEntity}'s synced
     * action / reaction packets: server bumps a sequence, client receives the
     * packet, looks up the matching {@link BakedAnimation}, and calls this
     * method on either the {@code "action"} or {@code "reaction"} controller.
     */
    public void playOnce(String controllerName, BakedAnimation animation, long nowNs) {
        ControllerInstance c = byName(controllerName);
        if (c != null) c.playOnce(animation, nowNs);
    }

    /** Look up a controller by name. {@code null} for unknown names. */
    public @Nullable ControllerInstance byName(String name) {
        for (ControllerInstance c : controllers) {
            if (c.name().equals(name)) return c;
        }
        return null;
    }

    /** Test-only: read controller list directly (registration order). */
    public List<ControllerInstance> controllers() {
        return controllers;
    }
}
