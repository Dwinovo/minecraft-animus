package com.dwinovo.animus.anim.format;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Gson deserialization target for Bedrock {@code .geo.json} (format_version 1.12.0). */
public final class BedrockGeoFile {

    @SerializedName("format_version")
    public String formatVersion;

    @SerializedName("minecraft:geometry")
    public List<Geometry> geometry;

    public static final class Geometry {
        public Description description;
        public List<Bone> bones;
    }

    public static final class Description {
        public String identifier;
        @SerializedName("texture_width")
        public int textureWidth;
        @SerializedName("texture_height")
        public int textureHeight;
    }

    public static final class Bone {
        public String name;
        public String parent;
        public float[] pivot;
        public float[] rotation;
        public List<Cube> cubes;
    }

    public static final class Cube {
        public float[] origin;
        public float[] size;
        public float[] pivot;
        public float[] rotation;
        public boolean mirror;
        /**
         * Polymorphic: either {@code [u, v]} (simple box UV) or
         * {@code {north: {uv,uv_size}, east: ..., south: ..., west: ..., up: ..., down: ...}} (per-face).
         * Resolved by {@link com.dwinovo.animus.anim.compile.ModelBaker}.
         */
        public JsonElement uv;
    }

    public static final class FaceUv {
        public float[] uv;
        @SerializedName("uv_size")
        public float[] uvSize;
    }
}
