package com.dwinovo.animus.anim.format;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Map;

/**
 * Gson deserialization target for Bedrock {@code render_controllers.json}
 * (format_version 1.8.0). Conforms to the full Bedrock schema so resource
 * packs authored for Bedrock add-ons can be dropped in unchanged, but only
 * {@code part_visibility} is consumed at bake time — {@code geometry /
 * materials / textures / color / on_fire_color} fields are parsed and held
 * here so the renderer can opt into them later without re-doing the loader.
 *
 * <p>Example:
 * <pre>
 * {
 *   "format_version": "1.8.0",
 *   "render_controllers": {
 *     "controller.render.hachiware": {
 *       "geometry": "Geometry.default",
 *       "materials": [{ "*": "Material.default" }],
 *       "textures": ["Texture.default"],
 *       "part_visibility": [
 *         { "*": true },
 *         { "guitar": "entity.task == 'play_music'" }
 *       ]
 *     }
 *   }
 * }
 * </pre>
 */
public final class BedrockRenderControllerFile {

    @SerializedName("format_version")
    public String formatVersion;

    /** Keyed by Bedrock convention {@code "controller.render.<id>"}. */
    @SerializedName("render_controllers")
    public Map<String, Controller> renderControllers;

    public static final class Controller {

        /** Bedrock reference to a {@code Geometry.*} array entry; ignored by Animus. */
        public String geometry;

        /** Material binding array; each entry is {@code {"<bone-glob>": "Material.<name>"}}. Ignored. */
        public List<JsonObject> materials;

        /** Texture array reference; ignored — texture identity lives on the entity. */
        public List<String> textures;

        /**
         * Per-bone visibility rules. Each list entry is a map of
         * {@code "<bone-glob>" → <expression>}, where the expression is either
         * a JSON boolean ({@code true}/{@code false}) or a Molang string.
         * Entries are evaluated in array order; later entries override earlier
         * ones (Bedrock "last-write-wins" semantics).
         */
        @SerializedName("part_visibility")
        public List<JsonObject> partVisibility;

        /** Color tint controller; Bedrock-vanilla feature, not consumed by Animus. */
        public JsonElement color;
        @SerializedName("on_fire_color")
        public JsonElement onFireColor;
        @SerializedName("overlay_color")
        public JsonElement overlayColor;
        @SerializedName("is_hurt_color")
        public JsonElement isHurtColor;
    }
}
