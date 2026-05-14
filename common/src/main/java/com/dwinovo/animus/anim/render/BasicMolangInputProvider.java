package com.dwinovo.animus.anim.render;

import com.dwinovo.animus.anim.molang.MolangContext;
import com.dwinovo.animus.anim.molang.MolangQueries;

/**
 * Default {@link BoneInputProvider} that wires the per-frame state values the
 * baseline animation depends on:
 *
 * <ul>
 *   <li>{@code query.ground_speed} ← {@link AnimusRenderState#walkSpeed}</li>
 * </ul>
 *
 * <p>{@code query.anim_time} is intentionally not set here — that slot is
 * filled by {@link com.dwinovo.animus.anim.runtime.PoseSampler} per channel
 * during sampling.
 */
public final class BasicMolangInputProvider implements BoneInputProvider {

    @Override
    public void fill(AnimusRenderState state, MolangContext ctx) {
        ctx.querySlots[MolangQueries.QUERY_GROUND_SPEED] = state.walkSpeed;
    }
}
