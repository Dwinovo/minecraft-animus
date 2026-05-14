package com.dwinovo.animus.anim.compile;

import com.dwinovo.animus.anim.baked.BakedBone;
import com.dwinovo.animus.anim.baked.BakedCube;
import com.dwinovo.animus.anim.baked.BakedModel;
import com.dwinovo.animus.anim.format.BedrockGeoFile;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compiles {@link BedrockGeoFile} POJOs into immutable {@link BakedModel}s. */
public final class ModelBaker {

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private ModelBaker() {}

    /** Test-only convenience overload (no bake stamp). */
    public static BakedModel bake(BedrockGeoFile file) {
        return bake(file, 0L);
    }

    public static BakedModel bake(BedrockGeoFile file, long bakeStamp) {
        if (file.geometry == null || file.geometry.isEmpty()) {
            throw new IllegalArgumentException("geo file has no geometry entries");
        }
        BedrockGeoFile.Geometry geo = file.geometry.get(0);
        int texW = geo.description.textureWidth;
        int texH = geo.description.textureHeight;
        if (geo.bones == null) {
            geo.bones = List.of();
        }

        int[] bfsOrder = bfsSortBones(geo.bones);
        Map<String, Integer> nameToIdx = new HashMap<>(bfsOrder.length * 2);
        for (int i = 0; i < bfsOrder.length; i++) {
            nameToIdx.put(geo.bones.get(bfsOrder[i]).name, i);
        }

        // Pre-compute children + roots in baked-index space for DFS render.
        List<List<Integer>> childrenOf = new ArrayList<>(bfsOrder.length);
        for (int i = 0; i < bfsOrder.length; i++) childrenOf.add(new ArrayList<>());
        List<Integer> rootList = new ArrayList<>();

        List<BakedCube> cubeList = new ArrayList<>(64);
        int[] parentIdxArr = new int[bfsOrder.length];

        for (int i = 0; i < bfsOrder.length; i++) {
            BedrockGeoFile.Bone src = geo.bones.get(bfsOrder[i]);
            int parentIdx = src.parent == null ? -1 : nameToIdx.getOrDefault(src.parent, -1);
            parentIdxArr[i] = parentIdx;
            if (parentIdx < 0) {
                rootList.add(i);
            } else {
                childrenOf.get(parentIdx).add(i);
            }
        }

        BakedBone[] bones = new BakedBone[bfsOrder.length];
        for (int i = 0; i < bfsOrder.length; i++) {
            BedrockGeoFile.Bone src = geo.bones.get(bfsOrder[i]);

            int cubeStart = cubeList.size();
            if (src.cubes != null) {
                for (BedrockGeoFile.Cube cube : src.cubes) {
                    cubeList.add(bakeCube(cube, src, texW, texH));
                }
            }
            int cubeCount = cubeList.size() - cubeStart;

            // Bake-time X flip into the renderer's coordinate frame.
            // Blockbench exports with display +X stored as JSON -X, so a bone whose
            // visual position is on the modeler's RIGHT (entity's left, JSON +X)
            // must render on the player's LEFT-of-entity side. We negate JSON X
            // here so the entity's local frame matches Minecraft's right-handed
            // entity space (model forward = -Z, model +X = entity's right). The
            // renderer then uses a clean positive scale and a Y rotation only.
            float pivotX = -pickF(src.pivot, 0, 0);
            float pivotY = pickF(src.pivot, 1, 0);
            float pivotZ = pickF(src.pivot, 2, 0);

            // Rotations: negate X and Y, keep Z. The Z axis is unaffected by the
            // X mirror; X/Y rotations reverse direction in the flipped frame.
            float rotX = src.rotation == null ? 0 : -src.rotation[0] * DEG_TO_RAD;
            float rotY = src.rotation == null ? 0 : -src.rotation[1] * DEG_TO_RAD;
            float rotZ = src.rotation == null ? 0 : src.rotation[2] * DEG_TO_RAD;
            boolean hasRest = rotX != 0 || rotY != 0 || rotZ != 0;

            int[] childArr = childrenOf.get(i).stream().mapToInt(Integer::intValue).toArray();
            bones[i] = new BakedBone(src.name, parentIdxArr[i],
                    pivotX, pivotY, pivotZ,
                    rotX, rotY, rotZ, hasRest,
                    cubeStart, cubeCount,
                    childArr);
        }

        int[] roots = rootList.stream().mapToInt(Integer::intValue).toArray();
        return new BakedModel(bones,
                cubeList.toArray(new BakedCube[0]),
                roots,
                nameToIdx,
                texW, texH,
                bakeStamp);
    }

    /** Returns indices into the source list in BFS order (roots first). */
    private static int[] bfsSortBones(List<BedrockGeoFile.Bone> raw) {
        int n = raw.size();
        Map<String, Integer> rawNameToIdx = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            rawNameToIdx.put(raw.get(i).name, i);
        }

        // Build child lists.
        List<List<Integer>> children = new ArrayList<>(n);
        List<Integer> roots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            children.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            BedrockGeoFile.Bone b = raw.get(i);
            if (b.parent == null) {
                roots.add(i);
            } else {
                Integer p = rawNameToIdx.get(b.parent);
                if (p == null) {
                    // Orphan: treat as root so we don't lose the bone.
                    roots.add(i);
                } else {
                    children.get(p).add(i);
                }
            }
        }

        int[] out = new int[n];
        int outIdx = 0;
        Deque<Integer> queue = new ArrayDeque<>();
        for (Integer r : roots) queue.add(r);
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            out[outIdx++] = idx;
            for (Integer c : children.get(idx)) {
                queue.add(c);
            }
        }
        if (outIdx != n) {
            // Cycle or disconnected: append any leftovers in source order.
            boolean[] seen = new boolean[n];
            for (int i = 0; i < outIdx; i++) seen[out[i]] = true;
            for (int i = 0; i < n; i++) {
                if (!seen[i]) out[outIdx++] = i;
            }
        }
        return out;
    }

    private static BakedCube bakeCube(BedrockGeoFile.Cube cube, BedrockGeoFile.Bone bone, int texW, int texH) {
        float ox = pickF(cube.origin, 0, 0);
        float oy = pickF(cube.origin, 1, 0);
        float oz = pickF(cube.origin, 2, 0);
        float sx = pickF(cube.size, 0, 0);
        float sy = pickF(cube.size, 1, 0);
        float sz = pickF(cube.size, 2, 0);

        // Bake-time X flip into the renderer's coordinate frame. JSON's [origin.x,
        // origin.x + size.x] becomes [-(origin.x + size.x), -origin.x]. The cube
        // keeps the same width but lives on the negated X side of the bone frame.
        float minX = -(ox + sx), minY = oy, minZ = oz;
        float maxX = -ox,        maxY = oy + sy, maxZ = oz + sz;

        // Cube pivot defaults to bone pivot when absent. Pivot.x is negated to
        // match the flipped origin frame.
        float[] pivotSrc = cube.pivot != null ? cube.pivot : bone.pivot;
        float pivotX = -pickF(pivotSrc, 0, 0);
        float pivotY = pickF(pivotSrc, 1, 0);
        float pivotZ = pickF(pivotSrc, 2, 0);

        // Cube rotations: negate X and Y to mirror, keep Z. Same reasoning as
        // the bone rotation flip above.
        float rotX = cube.rotation == null ? 0 : -cube.rotation[0] * DEG_TO_RAD;
        float rotY = cube.rotation == null ? 0 : -cube.rotation[1] * DEG_TO_RAD;
        float rotZ = cube.rotation == null ? 0 : cube.rotation[2] * DEG_TO_RAD;
        boolean hasRotation = rotX != 0 || rotY != 0 || rotZ != 0;

        float[][][] uv = bakeFaceUV(cube, sx, sy, sz);

        return new BakedCube(minX, minY, minZ, maxX, maxY, maxZ,
                pivotX, pivotY, pivotZ,
                rotX, rotY, rotZ, hasRotation,
                uv, texW, texH);
    }

    /**
     * Resolves the polymorphic {@code cube.uv} field into a per-face × per-vertex
     * UV array in pixel coordinates.
     *
     * <p>Vertex order per face is {@code TL → BL → BR → TR} (CCW from outside).
     */
    private static float[][][] bakeFaceUV(BedrockGeoFile.Cube cube, float sx, float sy, float sz) {
        float[][][] out = new float[6][4][2];
        if (cube.uv == null) {
            return out; // degenerate; will sample texel (0, 0) for everything
        }

        // Compensates for the bake-time X flip on cube geometry. After we negate
        // origin.x, what was JSON's max-X edge sits on the cube's visual LEFT in
        // model space. The texture was painted assuming the un-flipped Bedrock
        // layout, so we mirror U on non-mirror cubes to keep the painted right
        // edge on the cube's visual right. {@code mirror=true} cubes (the
        // deliberately-flipped mate of a symmetric pair, e.g. LeftEar paired with
        // RightEar) skip the U swap so the texture stays mirrored — exactly the
        // effect needed for the pair to look symmetric on screen.
        boolean uFlip = !cube.mirror;

        if (cube.uv.isJsonArray()) {
            // Simple box-UV form: cube.uv = [u, v]; layout follows Bedrock/Blockbench convention.
            float u = cube.uv.getAsJsonArray().get(0).getAsFloat();
            float v = cube.uv.getAsJsonArray().get(1).getAsFloat();
            simpleBoxUV(out, u, v, sx, sy, sz, uFlip);
        } else if (cube.uv.isJsonObject()) {
            JsonObject obj = cube.uv.getAsJsonObject();
            applyFaceUV(out, BakedCube.FACE_NORTH, obj, "north", uFlip);
            applyFaceUV(out, BakedCube.FACE_SOUTH, obj, "south", uFlip);
            applyFaceUV(out, BakedCube.FACE_WEST,  obj, "west",  uFlip);
            applyFaceUV(out, BakedCube.FACE_EAST,  obj, "east",  uFlip);
            applyFaceUV(out, BakedCube.FACE_UP,    obj, "up",    uFlip);
            applyFaceUV(out, BakedCube.FACE_DOWN,  obj, "down",  uFlip);
        }
        return out;
    }

    /**
     * Applies the standard Bedrock/Blockbench unwrapped-cross UV layout.
     * Width is X, height is Y, depth is Z.
     *
     * <pre>
     *           +d+   +w+   +w+
     *        ___________________
     *      h |    | UP | DN |    |
     *        |    |w×d |w×d |    |
     *        |____|____|____|____|
     *      h | E  | N  | W  | S  |
     *        |d×h |w×h |d×h |w×h |
     *        |____|____|____|____|
     * </pre>
     */
    private static void simpleBoxUV(float[][][] out, float u, float v, float w, float h, float d, boolean uFlip) {
        // Rectangles: (u0, v0, u1, v1)
        float upU0   = u + d,        upV0   = v,        upU1   = u + d + w,        upV1   = v + d;
        float downU0 = u + d + w,    downV0 = v,        downU1 = u + d + w + w,    downV1 = v + d;
        float eastU0 = u,            eastV0 = v + d,    eastU1 = u + d,            eastV1 = v + d + h;
        float northU0= u + d,        northV0= v + d,    northU1= u + d + w,        northV1= v + d + h;
        float westU0 = u + d + w,    westV0 = v + d,    westU1 = u + d + w + d,    westV1 = v + d + h;
        float southU0= u + d + w + d, southV0= v + d,   southU1= u + d + w + d + w, southV1= v + d + h;

        // Down face has V flipped per vanilla MC convention (texture wraps under the cube).
        setFaceQuadUV(out[BakedCube.FACE_DOWN],  downU0, downV1, downU1, downV0, uFlip);

        setFaceQuadUV(out[BakedCube.FACE_UP],    upU0,   upV0,   upU1,   upV1, uFlip);
        setFaceQuadUV(out[BakedCube.FACE_NORTH], northU0,northV0,northU1,northV1, uFlip);
        setFaceQuadUV(out[BakedCube.FACE_SOUTH], southU0,southV0,southU1,southV1, uFlip);
        setFaceQuadUV(out[BakedCube.FACE_WEST],  westU0, westV0, westU1, westV1, uFlip);
        setFaceQuadUV(out[BakedCube.FACE_EAST],  eastU0, eastV0, eastU1, eastV1, uFlip);
    }

    /** Per-face explicit UV from JSON. {@code uv} is top-left, {@code uv_size} can be negative. */
    private static void applyFaceUV(float[][][] out, int face, JsonObject parent, String key, boolean uFlip) {
        JsonElement el = parent.get(key);
        if (el == null || !el.isJsonObject()) return;
        JsonObject f = el.getAsJsonObject();
        JsonElement uvEl = f.get("uv");
        JsonElement sizeEl = f.get("uv_size");
        if (uvEl == null || sizeEl == null) return;
        float u0 = uvEl.getAsJsonArray().get(0).getAsFloat();
        float v0 = uvEl.getAsJsonArray().get(1).getAsFloat();
        float du = sizeEl.getAsJsonArray().get(0).getAsFloat();
        float dv = sizeEl.getAsJsonArray().get(1).getAsFloat();
        setFaceQuadUV(out[face], u0, v0, u0 + du, v0 + dv, uFlip);
    }

    /**
     * Fills 4 vertex UVs in TL → BL → BR → TR order (matching the geometry vertex
     * order in {@link com.dwinovo.animus.anim.render.ModelRenderer}). When
     * {@code uFlip} is true, the U coordinates are mirrored — the rectangle's
     * right edge ends up on the geometric TL/BL vertices and the left edge on
     * TR/BR. This compensates for the baked X mirror on non-mirrored cubes.
     */
    private static void setFaceQuadUV(float[][] dst, float u0, float v0, float u1, float v1, boolean uFlip) {
        if (uFlip) {
            float tmp = u0; u0 = u1; u1 = tmp;
        }
        // 0: TL
        dst[0][0] = u0; dst[0][1] = v0;
        // 1: BL
        dst[1][0] = u0; dst[1][1] = v1;
        // 2: BR
        dst[2][0] = u1; dst[2][1] = v1;
        // 3: TR
        dst[3][0] = u1; dst[3][1] = v0;
    }

    private static float pickF(float[] arr, int i, float def) {
        return arr == null || arr.length <= i ? def : arr[i];
    }
}
