package com.dwinovo.animus.anim.render.layer;

import com.dwinovo.animus.anim.baked.BakedModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.item.ItemStackRenderState;

/**
 * Renders the Animus's main-hand item, attached to a hand-locator bone in the
 * Bedrock model. The held item's {@link ItemStackRenderState} is resolved at
 * extract time (see {@code AnimusEntityRenderer.extractRenderState}); this layer
 * just walks the {@link PoseStack} to the hand bone and submits it.
 *
 * <h2>Hand bone convention</h2>
 * Tries {@link #HAND_BONES} in order and uses the first that exists in the
 * model. The default Hachiware model exposes {@code RightHandLocator}; other
 * common naming schemes are listed as fallbacks. A model with none of them
 * simply renders no held item (graceful no-op).
 *
 * <h2>Placement transform</h2>
 * The pose stack arrives in the model's <b>pixel-scaled</b> space (the renderer
 * applied a 1/16 scale, so 1 unit = 1 pixel and the locator pivots map straight
 * to it). Item models are authored in <b>block</b> space, so we undo that with a
 * ×16 ({@link #PIXEL_TO_BLOCK}) — without it the item renders at 1/16 size and is
 * effectively invisible. No extra rotation/offset is applied: the item sits at
 * the locator's own pivot + orientation, so the model's locator defines how it's
 * held.
 */
public final class HeldItemLayer implements RenderLayer {

    /** Candidate hand-locator bone names, tried in order. */
    private static final String[] HAND_BONES = {
            "RightHandLocator", "rightItem", "right_hand", "righthand",
            "RightArm", "right_arm", "mainhand", "item"
    };

    /** Undo the renderer's 1/16 pixel scale so the (block-space) item is visible. */
    private static final float PIXEL_TO_BLOCK = 16.0f;

    // Slight orientation tweak so the item sits more naturally in the hand.
    // Tune these against the model's locator; 0 on all three = raw locator pose.
    private static final float ROT_X_DEG = -45.0f;
    private static final float ROT_Y_DEG = 0.0f;
    private static final float ROT_Z_DEG = 0.0f;

    private final BoneTransformWalker walker = new BoneTransformWalker();

    @Override
    public void submit(RenderLayerContext ctx) {
        ItemStackRenderState item = ctx.state().heldItem;
        if (item.isEmpty()) return;

        int boneIdx = resolveHandBone(ctx.model());
        if (boneIdx < 0) return;   // model has no hand locator — nothing to attach to

        PoseStack ps = ctx.poseStack();
        ps.pushPose();
        walker.transformToBone(ctx.model(), ctx.poseBuf(), boneIdx, ps);
        ps.scale(PIXEL_TO_BLOCK, PIXEL_TO_BLOCK, PIXEL_TO_BLOCK);   // pixel → block space
        if (ROT_X_DEG != 0f) ps.mulPose(Axis.XP.rotationDegrees(ROT_X_DEG));
        if (ROT_Y_DEG != 0f) ps.mulPose(Axis.YP.rotationDegrees(ROT_Y_DEG));
        if (ROT_Z_DEG != 0f) ps.mulPose(Axis.ZP.rotationDegrees(ROT_Z_DEG));
        item.submit(ps, ctx.collector(), ctx.packedLight(), ctx.packedOverlay(), 0);
        ps.popPose();
    }

    private static int resolveHandBone(BakedModel model) {
        for (String name : HAND_BONES) {
            Integer idx = model.boneIndex.get(name);
            if (idx != null) return idx;
        }
        return -1;
    }
}
