package com.dwinovo.animus.anim.render.layer;

import com.dwinovo.animus.anim.baked.BakedBone;
import com.dwinovo.animus.anim.baked.BakedModel;
import com.dwinovo.animus.anim.runtime.PoseSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;

/**
 * Utility that pushes a frame onto a {@link PoseStack} matching a target
 * bone's pivot in world space. Reusable across {@link RenderLayer} implementations
 * that need to attach geometry to a named bone (held item, held prop, emissive
 * overlay aligned to a body part, etc.).
 *
 * <h2>Chain transform</h2>
 * Walks the bone chain root → target reproducing the same
 * {@code T(pivot + dPos) · R(rest + delta) · S · T(-pivot)} composition that
 * {@link com.dwinovo.animus.anim.render.ModelRenderer} applies. For the
 * chain's last bone the trailing {@code T(-pivot)} is omitted so the
 * {@link PoseStack} ends up at the locator's pivot (with parent rotations
 * applied), which is the natural anchor for downstream display transforms.
 *
 * <h2>Lifecycle</h2>
 * Instances cache a small reusable {@code int[]} chain buffer and a
 * {@link Quaternionf} — a single instance per {@link RenderLayer} is fine,
 * but the walker is <b>not</b> thread-safe.
 */
public final class BoneTransformWalker {

    private final Quaternionf rotBuf = new Quaternionf();
    /** Reusable buffer for the chain root → target. */
    private int[] chainBuf = new int[8];

    /**
     * Pushes the chain transform root → {@code targetBoneIdx} onto
     * {@code poseStack}. Caller is responsible for surrounding
     * {@code pushPose}/{@code popPose}.
     */
    public void transformToBone(BakedModel model, float[] poseBuf, int targetBoneIdx,
                                PoseStack poseStack) {
        int chainLen = buildChain(model, targetBoneIdx);
        for (int i = 0; i < chainLen; i++) {
            applyBoneTransform(model, poseBuf, chainBuf[i], poseStack, i == chainLen - 1);
        }
    }

    /** Fills {@link #chainBuf} with bone indices root → target. Returns the length. */
    private int buildChain(BakedModel model, int targetIdx) {
        int len = 0;
        int idx = targetIdx;
        while (idx >= 0) {
            len++;
            idx = model.bones[idx].parentIdx;
        }
        if (chainBuf.length < len) chainBuf = new int[Math.max(len, chainBuf.length * 2)];
        idx = targetIdx;
        for (int i = len - 1; i >= 0; i--) {
            chainBuf[i] = idx;
            idx = model.bones[idx].parentIdx;
        }
        return len;
    }

    private void applyBoneTransform(BakedModel model, float[] poseBuf, int boneIdx,
                                    PoseStack poseStack, boolean isTarget) {
        BakedBone bone = model.bones[boneIdx];
        int base = boneIdx * PoseSampler.FLOATS_PER_BONE;
        float dRotX = poseBuf[base];
        float dRotY = poseBuf[base + 1];
        float dRotZ = poseBuf[base + 2];
        float dPosX = poseBuf[base + 3];
        float dPosY = poseBuf[base + 4];
        float dPosZ = poseBuf[base + 5];
        float sX    = poseBuf[base + 6];
        float sY    = poseBuf[base + 7];
        float sZ    = poseBuf[base + 8];

        float rotX = bone.restRotX + dRotX;
        float rotY = bone.restRotY + dRotY;
        float rotZ = bone.restRotZ + dRotZ;
        boolean hasRot   = rotX != 0f || rotY != 0f || rotZ != 0f;
        boolean hasScale = sX != 1f || sY != 1f || sZ != 1f;
        boolean hasPos   = dPosX != 0f || dPosY != 0f || dPosZ != 0f;

        if (isTarget) {
            // Target bone is the anchor — translate to (pivot+dPos) so the
            // PoseStack ends at the locator's pivot, with rotation/scale composed
            // on top. The trailing T(-pivot) is intentionally omitted so attached
            // geometry hangs at the pivot rather than absolute zero.
            poseStack.translate(bone.pivotX + dPosX, bone.pivotY + dPosY, bone.pivotZ + dPosZ);
            if (hasRot) {
                rotBuf.identity().rotationXYZ(rotX, rotY, rotZ);
                poseStack.last().rotate(rotBuf);
            }
            if (hasScale) {
                poseStack.scale(sX, sY, sZ);
            }
            return;
        }

        // Non-target chain links: same identity / pos-only fast paths as
        // ModelRenderer.renderBone — collapse the pivot sandwich when no
        // rotation/scale is in play, no-op when nothing is in play.
        if (hasRot || hasScale) {
            poseStack.translate(bone.pivotX + dPosX, bone.pivotY + dPosY, bone.pivotZ + dPosZ);
            if (hasRot) {
                rotBuf.identity().rotationXYZ(rotX, rotY, rotZ);
                poseStack.last().rotate(rotBuf);
            }
            if (hasScale) {
                poseStack.scale(sX, sY, sZ);
            }
            poseStack.translate(-bone.pivotX, -bone.pivotY, -bone.pivotZ);
        } else if (hasPos) {
            poseStack.translate(dPosX, dPosY, dPosZ);
        }
    }
}
