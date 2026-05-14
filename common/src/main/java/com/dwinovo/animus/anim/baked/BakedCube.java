package com.dwinovo.animus.anim.baked;

/**
 * Baked, render-ready cube. All angle units are radians, all positions are in
 * Bedrock pixel-space (1 unit = 1/16 block). Per-face UV is stored as four
 * vertex UVs in face-local CCW order to avoid runtime conditionals.
 *
 * Memory layout is intentionally flat to be cache-friendly and to translate
 * 1:1 to a future Rust struct if the renderer is ever migrated to native.
 */
public final class BakedCube {
    /** Cube min corner (post initial cube rotation: BEFORE rotation, in bone-local pixel space). */
    public final float minX, minY, minZ;
    /** Cube max corner. */
    public final float maxX, maxY, maxZ;

    /** Pivot for this cube's own rotation (in the same space as min/max). */
    public final float pivotX, pivotY, pivotZ;
    /** Per-cube rotation in radians, applied around (pivotX, pivotY, pivotZ). XYZ Euler. */
    public final float rotX, rotY, rotZ;
    public final boolean hasRotation;

    /**
     * Per-face UV in pixel coordinates: [face][vert] -> {u, v}. Face order:
     * 0=NORTH(-Z), 1=SOUTH(+Z), 2=WEST(-X), 3=EAST(+X), 4=UP(+Y), 5=DOWN(-Y).
     * Vertex order per face is CCW when viewed from outside the cube.
     */
    public final float[][][] faceUV;

    /** Texture width in pixels (for normalising UVs at draw time). */
    public final int texW;
    /** Texture height in pixels. */
    public final int texH;

    public BakedCube(float minX, float minY, float minZ,
                     float maxX, float maxY, float maxZ,
                     float pivotX, float pivotY, float pivotZ,
                     float rotX, float rotY, float rotZ,
                     boolean hasRotation,
                     float[][][] faceUV,
                     int texW, int texH) {
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        this.pivotX = pivotX; this.pivotY = pivotY; this.pivotZ = pivotZ;
        this.rotX = rotX; this.rotY = rotY; this.rotZ = rotZ;
        this.hasRotation = hasRotation;
        this.faceUV = faceUV;
        this.texW = texW;
        this.texH = texH;
    }

    public static final int FACE_NORTH = 0;
    public static final int FACE_SOUTH = 1;
    public static final int FACE_WEST  = 2;
    public static final int FACE_EAST  = 3;
    public static final int FACE_UP    = 4;
    public static final int FACE_DOWN  = 5;
}
