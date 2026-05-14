package com.dwinovo.animus.anim.render.layer;

import com.dwinovo.animus.anim.baked.BakedModel;
import com.dwinovo.animus.anim.render.AnimusRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * Per-submit data passed to every {@link RenderLayer}. The PoseStack is at the
 * entity's body-rotation frame with the 1/16 pixel scale already applied —
 * the same state {@link com.dwinovo.animus.anim.render.ModelRenderer} sees
 * when it walks the main mesh.
 *
 * <p>The pose buffer is the post-interceptor flat array (rot/pos/scale per
 * bone) that {@link com.dwinovo.animus.anim.runtime.PoseSampler} produced
 * for this frame. Layers that need a specific bone's world transform should
 * use {@link BoneTransformWalker} rather than recomputing the chain walk.
 *
 * @param model         the resolved baked model for this entity
 * @param poseBuf       per-bone (rot, pos, scale) flat array, indexed by bone idx
 * @param state         render-state snapshot captured at extract time
 * @param poseStack     entity-rooted PoseStack (caller manages push/pop around the layer loop)
 * @param collector     submit target for any geometry the layer emits
 * @param packedLight   packed light coords for this entity
 * @param packedOverlay packed overlay coords (hurt flash, etc.)
 */
public record RenderLayerContext(
        BakedModel model,
        float[] poseBuf,
        AnimusRenderState state,
        PoseStack poseStack,
        SubmitNodeCollector collector,
        int packedLight,
        int packedOverlay
) {
}
