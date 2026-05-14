package com.dwinovo.animus.anim.render;

import com.dwinovo.animus.anim.baked.BakedBone;
import com.dwinovo.animus.anim.baked.BakedCube;
import com.dwinovo.animus.anim.baked.BakedModel;
import com.dwinovo.animus.anim.runtime.PoseSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Quaternionf;

/**
 * Stateless mesh emitter. Walks a {@link BakedModel}'s bone DAG with a
 * {@link PoseStack} and pushes 6 face quads per cube into the supplied
 * vertex consumer.
 *
 * <p>For each bone it composes:
 * <pre>
 *   M_bone = M_parent
 *          * T(pivot + animPos)
 *          * R(restRot + animRot)
 *          * S(animScale)
 *          * T(-pivot)
 * </pre>
 * which gives, for a vertex {@code v} in absolute pixel coords,
 * {@code R * S * (v - pivot) + pivot + animPos} in the parent frame —
 * scale and rotation around the (animated) pivot, plus a clean
 * translation by the animation offset.
 */
public final class ModelRenderer {

    /**
     * Per-face vertex order: TL, BL, BR, TR (CCW from outside the cube).
     * Indices reference the 8 cube corners encoded as a 3-bit mask:
     * bit0=X(max if 1), bit1=Y(max if 1), bit2=Z(max if 1).
     */
    private static final int[][] FACE_CORNERS = {
            // NORTH (-Z): viewed from -Z; +X visually on left.
            {0b011, 0b001, 0b000, 0b010},
            // SOUTH (+Z): viewed from +Z; +X on right.
            {0b110, 0b100, 0b101, 0b111},
            // WEST (-X)
            {0b010, 0b000, 0b100, 0b110},
            // EAST (+X)
            {0b111, 0b101, 0b001, 0b011},
            // UP (+Y): TL = NW corner, +X right, +Z down.
            {0b010, 0b110, 0b111, 0b011},
            // DOWN (-Y): TL = SW corner.
            {0b100, 0b000, 0b001, 0b101},
    };

    private static final float[][] FACE_NORMALS = {
            {0, 0, -1},
            {0, 0, 1},
            {-1, 0, 0},
            {1, 0, 0},
            {0, 1, 0},
            {0, -1, 0},
    };

    private final Quaternionf rotBuf = new Quaternionf();

    /**
     * Renders the entire model with the supplied {@code poseBuf} (laid out per
     * {@link PoseSampler}). The caller's {@code initial} pose should already
     * include the entity transform, body rotation, and pixel→block scale.
     *
     * <p>{@code hiddenBones} is an optional per-bone flag array (length must
     * equal {@code model.bones.length} when non-null and non-empty). Bones
     * marked hidden — and their entire subtree — are skipped: no matrix push,
     * no cube emission, no recursion into children. Pass {@code null} or an
     * empty array when the renderer has no visibility rules registered.
     */
    public void render(BakedModel model, PoseStack.Pose initial, VertexConsumer vc,
                       int packedLight, int packedOverlay, float[] poseBuf,
                       boolean[] hiddenBones) {
        PoseStack stack = new PoseStack();
        stack.last().set(initial);
        boolean[] hidden = (hiddenBones != null && hiddenBones.length == model.bones.length)
                ? hiddenBones : null;
        for (int rootIdx : model.rootBones) {
            renderBone(model, rootIdx, stack, vc, packedLight, packedOverlay, poseBuf, hidden);
        }
    }

    private void renderBone(BakedModel model, int boneIdx, PoseStack stack,
                            VertexConsumer vc, int packedLight, int packedOverlay,
                            float[] poseBuf, boolean[] hiddenBones) {
        if (hiddenBones != null && hiddenBones[boneIdx]) {
            // Hide bone + entire subtree. Early return BEFORE push/translate/rotate
            // so the parent's transform isn't disturbed for sibling subtrees.
            return;
        }
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
        boolean modifiesPose = hasRot || hasScale || hasPos;

        // Skip the push/pop entirely for identity bones (the common case for
        // organizational bones with no rest rotation and no animation slot).
        // For pos-only bones the full T(pivot+dPos) · I · I · T(-pivot)
        // sandwich collapses to a single T(dPos), saving one matrix mul.
        if (modifiesPose) {
            stack.pushPose();
            if (hasRot || hasScale) {
                stack.translate(bone.pivotX + dPosX, bone.pivotY + dPosY, bone.pivotZ + dPosZ);
                if (hasRot) {
                    rotBuf.identity().rotationXYZ(rotX, rotY, rotZ);
                    stack.last().rotate(rotBuf);
                }
                if (hasScale) {
                    stack.scale(sX, sY, sZ);
                }
                stack.translate(-bone.pivotX, -bone.pivotY, -bone.pivotZ);
            } else {
                stack.translate(dPosX, dPosY, dPosZ);
            }
        }

        for (int c = bone.cubeStart, end = bone.cubeStart + bone.cubeCount; c < end; c++) {
            renderCube(model.cubes[c], stack, vc, packedLight, packedOverlay);
        }
        for (int childIdx : bone.children) {
            renderBone(model, childIdx, stack, vc, packedLight, packedOverlay, poseBuf, hiddenBones);
        }

        if (modifiesPose) {
            stack.popPose();
        }
    }

    private void renderCube(BakedCube cube, PoseStack stack, VertexConsumer vc,
                            int packedLight, int packedOverlay) {
        stack.pushPose();
        if (cube.hasRotation) {
            rotBuf.identity().rotationXYZ(cube.rotX, cube.rotY, cube.rotZ);
            stack.last().rotateAround(rotBuf, cube.pivotX, cube.pivotY, cube.pivotZ);
        }

        PoseStack.Pose pose = stack.last();
        float invW = 1.0f / cube.texW;
        float invH = 1.0f / cube.texH;

        for (int face = 0; face < 6; face++) {
            int[] corners = FACE_CORNERS[face];
            float[] n = FACE_NORMALS[face];
            for (int v = 0; v < 4; v++) {
                int mask = corners[v];
                float x = ((mask & 1) != 0) ? cube.maxX : cube.minX;
                float y = ((mask & 2) != 0) ? cube.maxY : cube.minY;
                float z = ((mask & 4) != 0) ? cube.maxZ : cube.minZ;
                float u = cube.faceUV[face][v][0] * invW;
                float vv = cube.faceUV[face][v][1] * invH;
                vc.addVertex(pose, x, y, z)
                        .setColor(255, 255, 255, 255)
                        .setUv(u, vv)
                        .setOverlay(packedOverlay)
                        .setLight(packedLight)
                        .setNormal(pose, n[0], n[1], n[2]);
            }
        }

        stack.popPose();
    }
}
