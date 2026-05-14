package com.dwinovo.animus.anim.render;

import com.dwinovo.animus.anim.molang.MolangContext;
import com.dwinovo.animus.anim.molang.MolangQueries;

/**
 * {@link BoneInputProvider} that wires entity-derived state into the
 * Bedrock-standard {@code query.*} namespace. Registered automatically by
 * {@link AnimusEntityRenderer}, so every Animus-style renderer carries these
 * slots without subclasses opting in.
 *
 * <h2>Provided queries</h2>
 * <ul>
 *   <li>{@code query.task} ← FNV hash of {@link AnimusRenderState#entityTaskHash}</li>
 *   <li>{@code query.is_on_ground} / {@code query.is_in_water} ← booleans as 0/1</li>
 *   <li>{@code query.health} / {@code query.max_health}</li>
 *   <li>{@code query.scale}</li>
 *   <li>{@code query.body_y_rotation} / {@code query.head_y_rotation} (degrees)</li>
 * </ul>
 *
 * <p>{@code query.anim_time} and {@code query.ground_speed} are filled
 * elsewhere (PoseSampler / BasicMolangInputProvider) — they have their own
 * fill points so this provider doesn't double-write.
 */
public final class EntityMolangInputProvider implements BoneInputProvider {

    @Override
    public void fill(AnimusRenderState state, MolangContext ctx) {
        double[] q = ctx.querySlots;
        q[MolangQueries.QUERY_TASK]            = state.entityTaskHash;
        q[MolangQueries.QUERY_IS_ON_GROUND]    = state.isOnGround ? 1.0 : 0.0;
        q[MolangQueries.QUERY_IS_IN_WATER]     = state.isInWater  ? 1.0 : 0.0;
        q[MolangQueries.QUERY_HEALTH]          = state.health;
        q[MolangQueries.QUERY_MAX_HEALTH]      = state.maxHealth;
        q[MolangQueries.QUERY_SCALE]           = state.scale;
        q[MolangQueries.QUERY_BODY_Y_ROTATION] = state.bodyRot;
        q[MolangQueries.QUERY_HEAD_Y_ROTATION] = state.yRot;
    }
}
