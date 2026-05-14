package com.dwinovo.animus.anim.baked;

import java.util.Map;

/**
 * Immutable, render-ready model. Shared across all entity instances of the
 * same type — never mutated after baking.
 *
 * Bones are stored in BFS order, so iterating them once renders the entire
 * skeleton with parent transforms always seen before children.
 *
 * <h2>Bake stamp</h2>
 * {@link #bakeStamp} is the {@link BakeStamp} value at the moment the model
 * was baked. After a resource reload all baked objects are tagged with a
 * fresh stamp; channels still referencing the old generation can be detected
 * by comparing stamps and dropped before their stale bone indices reach the
 * sampler.
 */
public final class BakedModel {

    public final BakedBone[] bones;
    public final BakedCube[] cubes;
    /** Indices of bones with no parent (entry points for DFS render). */
    public final int[] rootBones;
    /** Bone name → index lookup. Used by animation channels at bake time. */
    public final Map<String, Integer> boneIndex;

    public final int textureWidth;
    public final int textureHeight;

    /** {@link BakeStamp} value at the moment this model was baked. */
    public final long bakeStamp;

    /** Test-only: defaults {@link #bakeStamp} to {@code 0} (unset). */
    public BakedModel(BakedBone[] bones, BakedCube[] cubes, int[] rootBones,
                      Map<String, Integer> boneIndex,
                      int textureWidth, int textureHeight) {
        this(bones, cubes, rootBones, boneIndex, textureWidth, textureHeight, 0L);
    }

    public BakedModel(BakedBone[] bones, BakedCube[] cubes, int[] rootBones,
                      Map<String, Integer> boneIndex,
                      int textureWidth, int textureHeight,
                      long bakeStamp) {
        this.bones = bones;
        this.cubes = cubes;
        this.rootBones = rootBones;
        this.boneIndex = boneIndex;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.bakeStamp = bakeStamp;
    }
}
