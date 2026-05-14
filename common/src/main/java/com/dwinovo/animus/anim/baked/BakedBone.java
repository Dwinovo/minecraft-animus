package com.dwinovo.animus.anim.baked;

/**
 * Bone in BFS-flattened skeleton. Children reference parent by integer index
 * to avoid pointer chasing during pose evaluation.
 */
public final class BakedBone {

    public final String name;
    /** Parent index in {@link BakedModel#bones}, or -1 if this is a root bone. */
    public final int parentIdx;

    /** Bone pivot in absolute pixel space (Bedrock convention: world-space). */
    public final float pivotX, pivotY, pivotZ;
    /** Initial bone rotation in radians (XYZ Euler). Identity if all zero. */
    public final float restRotX, restRotY, restRotZ;
    public final boolean hasRestRotation;

    /** Range of cubes belonging to this bone in {@link BakedModel#cubes}. */
    public final int cubeStart;
    public final int cubeCount;

    /**
     * Child bone indices, in BFS order. Pre-baked so the renderer can do a
     * recursive DFS render without building child lists every frame.
     */
    public final int[] children;

    public BakedBone(String name, int parentIdx,
                     float pivotX, float pivotY, float pivotZ,
                     float restRotX, float restRotY, float restRotZ,
                     boolean hasRestRotation,
                     int cubeStart, int cubeCount,
                     int[] children) {
        this.name = name;
        this.parentIdx = parentIdx;
        this.pivotX = pivotX; this.pivotY = pivotY; this.pivotZ = pivotZ;
        this.restRotX = restRotX; this.restRotY = restRotY; this.restRotZ = restRotZ;
        this.hasRestRotation = hasRestRotation;
        this.cubeStart = cubeStart;
        this.cubeCount = cubeCount;
        this.children = children;
    }
}
