package com.dwinovo.animus.anim.render;

import com.dwinovo.animus.anim.molang.MolangContext;

/**
 * Pre-sampling stage that fills the {@link MolangContext} with values derived
 * from {@link AnimusRenderState} (or any other client-side source).
 *
 * <p>Conceptually the inverse of {@link BoneInterceptor}: providers run
 * <em>before</em> {@link com.dwinovo.animus.anim.runtime.PoseSampler} so
 * MoLang expressions evaluated during sampling have the right inputs;
 * interceptors run <em>after</em> sampling to override specific bone slots
 * with values that don't fit MoLang's pure-expression model.
 *
 * <p>Each provider only writes to a subset of {@link MolangContext}'s slot
 * arrays — for instance, the default {@code BasicMolangInputProvider} only
 * sets {@code query.ground_speed}. Adding a new MoLang variable means:
 * register a slot on {@link com.dwinovo.animus.anim.molang.MolangQueries},
 * then register a provider (or extend an existing one) that fills it. The
 * submit path itself does not change.
 *
 * <p>Providers are stateless aside from immutable configuration, just like
 * {@link com.dwinovo.animus.anim.render.layer.RenderLayer}.
 */
@FunctionalInterface
public interface BoneInputProvider {

    void fill(AnimusRenderState state, MolangContext ctx);
}
