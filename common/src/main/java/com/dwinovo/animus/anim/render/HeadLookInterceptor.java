package com.dwinovo.animus.anim.render;

import com.dwinovo.animus.anim.baked.BakedModel;
import com.dwinovo.animus.anim.molang.MolangContext;
import com.dwinovo.animus.anim.runtime.PoseSampler;

/**
 * {@link BoneInterceptor.Stage#LOOK_AT} interceptor that points {@code AllHead}
 * along the entity's view direction.
 *
 * <p>Reads {@link AnimusRenderState#netHeadYaw} and {@link AnimusRenderState#headPitch}
 * — both captured at extract time — rather than the live {@code yRot} /
 * {@code bodyRot} fields, which {@code InventoryScreen} overwrites with
 * mouse-derived values during inventory previews.
 */
public final class HeadLookInterceptor implements BoneInterceptor {

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    @Override
    public void apply(BakedModel model, AnimusRenderState state, MolangContext ctx, float[] poseBuf) {
        Integer headIdx = model.boneIndex.get("AllHead");
        if (headIdx == null) return;
        int base = headIdx * PoseSampler.FLOATS_PER_BONE;
        poseBuf[base]     = -state.headPitch  * DEG_TO_RAD;  // X (pitch)
        poseBuf[base + 1] = -state.netHeadYaw * DEG_TO_RAD;  // Y (yaw)
        poseBuf[base + 2] = 0f;                              // Z
    }
}
