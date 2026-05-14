package com.dwinovo.animus.anim.compile;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.anim.api.AnimationLibrary;
import com.dwinovo.animus.anim.api.ModelLibrary;
import com.dwinovo.animus.anim.api.ModelManifestLibrary;
import com.dwinovo.animus.anim.api.RenderControllerLibrary;
import com.dwinovo.animus.anim.baked.BakeStamp;
import com.dwinovo.animus.anim.baked.BakedAnimation;
import com.dwinovo.animus.anim.baked.BakedModel;
import com.dwinovo.animus.anim.baked.BakedModelManifest;
import com.dwinovo.animus.anim.baked.BakedRenderController;
import com.dwinovo.animus.anim.format.BedrockGeoFile;
import com.dwinovo.animus.anim.format.BedrockModelManifest;
import com.dwinovo.animus.anim.format.BedrockRenderControllerFile;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Second-source model loader that scans player-supplied Bedrock model files
 * from {@code <gameDir>/config/animus/models/} and bakes them into the
 * {@code animus_user} namespace.
 *
 * <h2>Layout — one directory per model</h2>
 * <pre>
 * config/animus/models/
 *   &lt;id&gt;/
 *     geometry.json          (required — Bedrock .geo.json)
 *     animation.json         (optional — Bedrock animation file, may contain
 *                            multiple animations)
 *     render_controller.json (optional — Bedrock render_controllers schema)
 *     manifest.json          (optional — display name / description / author)
 *     texture.png            (required for rendering)
 * </pre>
 *
 * <p>The directory name becomes the model id: e.g.
 * {@code config/animus/models/my_skin/} registers as
 * {@code animus_user:my_skin}. Animations within {@code animation.json}
 * become {@code animus_user:my_skin/<anim_name>}.
 *
 * <p>Texture loading is handled separately by {@link ConfigTextureLoader} —
 * textures must round-trip through Minecraft's {@code TextureManager} rather
 * than the model library.
 *
 * <h2>Why by-model instead of by-type</h2>
 * Mod-shipped assets live under {@code assets/animus/} and follow the
 * vanilla resource-pack convention (separate {@code models/entity/},
 * {@code animations/}, {@code textures/entities/} top-level dirs) because
 * vanilla's {@code ResourceManager} indexes by path prefix. The
 * player-facing config directory has the opposite priority: a single model
 * is one self-contained folder the user can drop in or zip up, so files
 * stay together by model id, not by type.
 */
public final class ConfigModelLoader {

    public static final String CONFIG_NAMESPACE = "animus_user";

    // Per-model file names. These are the contract authors and the
    // upcoming .animuspack importer share.
    public static final String GEOMETRY_FILE          = "geometry.json";
    public static final String ANIMATION_FILE         = "animation.json";
    public static final String RENDER_CONTROLLER_FILE = "render_controller.json";
    public static final String MANIFEST_FILE          = "manifest.json";
    public static final String TEXTURE_FILE           = "texture.png";

    private static final Gson GSON = new Gson();

    private ConfigModelLoader() {}

    /** Output of a scan; all maps may be empty but never null. */
    public record Result(Map<Identifier, BakedModel> models,
                         Map<Identifier, BakedAnimation> animations,
                         Map<Identifier, BakedRenderController> renderControllers,
                         Map<Identifier, BakedModelManifest> manifests) {
        public static final Result EMPTY = new Result(Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Stats returned by {@link #rescan} — used by the GUI to show a confirmation toast. */
    public record RescanStats(int models, int animations, int renderControllers, int manifests) {
        public static final RescanStats EMPTY = new RescanStats(0, 0, 0, 0);
    }

    public static Result scan(Path configDir, long stamp) {
        if (configDir == null || !Files.isDirectory(configDir)) return Result.EMPTY;

        Map<Identifier, BakedModel> models = new HashMap<>();
        Map<Identifier, BakedAnimation> animations = new HashMap<>();
        Map<Identifier, BakedRenderController> renderControllers = new HashMap<>();
        Map<Identifier, BakedModelManifest> manifests = new HashMap<>();

        try (Stream<Path> dirs = Files.list(configDir)) {
            dirs.filter(Files::isDirectory)
                .sorted()
                .forEach(modelDir -> loadOneModel(modelDir, stamp,
                        models, animations, renderControllers, manifests));
        } catch (IOException ex) {
            Constants.LOG.error("[animus-anim] failed to walk config models dir {}: {}",
                    configDir, ex.toString());
        }

        return new Result(models, animations, renderControllers, manifests);
    }

    /**
     * Re-scan {@code configDir} and atomically replace the {@code animus_user}
     * namespace in every baked-asset library — vanilla {@code animus} entries
     * stay untouched. Used by the model-chooser GUI's "refresh" button so
     * authors iterating on custom models don't have to trigger a full
     * ResourceManager reload.
     */
    public static RescanStats rescan(Path configDir) {
        if (configDir == null || !Files.isDirectory(configDir)) {
            ModelLibrary.replaceNamespace(CONFIG_NAMESPACE, Map.of());
            AnimationLibrary.replaceNamespace(CONFIG_NAMESPACE, Map.of());
            RenderControllerLibrary.replaceNamespace(CONFIG_NAMESPACE, Map.of());
            ModelManifestLibrary.replaceNamespace(CONFIG_NAMESPACE, Map.of());
            return RescanStats.EMPTY;
        }
        long stamp = BakeStamp.next();
        Result result = scan(configDir, stamp);
        ModelLibrary.replaceNamespace(CONFIG_NAMESPACE, result.models());
        AnimationLibrary.replaceNamespace(CONFIG_NAMESPACE, result.animations());
        RenderControllerLibrary.replaceNamespace(CONFIG_NAMESPACE, result.renderControllers());
        ModelManifestLibrary.replaceNamespace(CONFIG_NAMESPACE, result.manifests());
        ConfigTextureLoader.scan(configDir);
        Constants.LOG.info("[animus-anim] config rescan: {} models, {} animations, {} render_controllers, {} manifests (stamp {})",
                result.models().size(), result.animations().size(),
                result.renderControllers().size(), result.manifests().size(), stamp);
        return new RescanStats(result.models().size(), result.animations().size(),
                result.renderControllers().size(), result.manifests().size());
    }

    /**
     * Bake every supported asset present in one model directory. Missing
     * optional files are silently skipped; missing geometry is the only
     * fatal case (without a model, animations / RC / manifest have nothing
     * to bind to).
     */
    private static void loadOneModel(Path modelDir, long stamp,
                                     Map<Identifier, BakedModel> models,
                                     Map<Identifier, BakedAnimation> animations,
                                     Map<Identifier, BakedRenderController> renderControllers,
                                     Map<Identifier, BakedModelManifest> manifests) {
        String id = modelDir.getFileName().toString();
        Identifier modelKey = Identifier.fromNamespaceAndPath(CONFIG_NAMESPACE, id);

        Path geoPath = modelDir.resolve(GEOMETRY_FILE);
        if (!Files.isRegularFile(geoPath)) {
            Constants.LOG.warn("[animus-anim] config model '{}' has no {} — skipping",
                    id, GEOMETRY_FILE);
            return;
        }
        BakedModel model;
        try (BufferedReader r = Files.newBufferedReader(geoPath)) {
            BedrockGeoFile file = GSON.fromJson(r, BedrockGeoFile.class);
            model = ModelBaker.bake(file, stamp);
        } catch (Exception ex) {
            Constants.LOG.error("[animus-anim] failed to load {}/{}: {}",
                    id, GEOMETRY_FILE, ex.toString());
            return;
        }
        models.put(modelKey, model);

        Path animPath = modelDir.resolve(ANIMATION_FILE);
        if (Files.isRegularFile(animPath)) {
            try (BufferedReader r = Files.newBufferedReader(animPath)) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                Map<String, BakedAnimation> anims = AnimationBaker.bake(root, model);
                for (Map.Entry<String, BakedAnimation> a : anims.entrySet()) {
                    Identifier animId = Identifier.fromNamespaceAndPath(CONFIG_NAMESPACE,
                            id + "/" + a.getKey());
                    animations.put(animId, a.getValue());
                }
            } catch (Exception ex) {
                Constants.LOG.error("[animus-anim] failed to load {}/{}: {}",
                        id, ANIMATION_FILE, ex.toString());
            }
        }

        Path rcPath = modelDir.resolve(RENDER_CONTROLLER_FILE);
        if (Files.isRegularFile(rcPath)) {
            try (BufferedReader r = Files.newBufferedReader(rcPath)) {
                BedrockRenderControllerFile file = GSON.fromJson(r, BedrockRenderControllerFile.class);
                BakedRenderController rc = RenderControllerBaker.bake(file, stamp);
                if (rc != null) renderControllers.put(modelKey, rc);
            } catch (Exception ex) {
                Constants.LOG.error("[animus-anim] failed to load {}/{}: {}",
                        id, RENDER_CONTROLLER_FILE, ex.toString());
            }
        }

        Path manifestPath = modelDir.resolve(MANIFEST_FILE);
        if (Files.isRegularFile(manifestPath)) {
            try (BufferedReader r = Files.newBufferedReader(manifestPath)) {
                BedrockModelManifest file = GSON.fromJson(r, BedrockModelManifest.class);
                manifests.put(modelKey, ModelManifestBaker.bake(file, stamp));
            } catch (Exception ex) {
                Constants.LOG.error("[animus-anim] failed to load {}/{}: {}",
                        id, MANIFEST_FILE, ex.toString());
            }
        }
    }
}
