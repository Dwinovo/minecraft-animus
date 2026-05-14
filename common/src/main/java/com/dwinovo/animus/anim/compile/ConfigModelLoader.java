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
 * {@code animus_user} namespace. Mirrors the layout of vanilla resource packs
 * so artists can drop in / iterate on a model without packaging a resource
 * pack:
 *
 * <pre>
 * config/animus/models/
 *   models/entity/&lt;id&gt;.json
 *   animations/&lt;id&gt;.json
 *   render_controllers/&lt;id&gt;.json
 *   textures/entities/&lt;id&gt;.png
 * </pre>
 *
 * <p>Returns the baked models / animations / render controllers as plain maps;
 * the caller (the shared resource-reload entry point) merges them with the
 * vanilla {@code animus} namespace and replaces the global libraries in one
 * atomic swap.
 *
 * <p>Texture loading is handled separately by {@link ConfigTextureLoader} —
 * textures must round-trip through Minecraft's {@code TextureManager} rather
 * than the model library.
 */
public final class ConfigModelLoader {

    public static final String CONFIG_NAMESPACE = "animus_user";

    private static final Gson GSON = new Gson();

    private ConfigModelLoader() {}

    /** Output of a scan; all maps may be empty but never null. */
    public record Result(Map<Identifier, BakedModel> models,
                         Map<Identifier, BakedAnimation> animations,
                         Map<Identifier, BakedRenderController> renderControllers,
                         Map<Identifier, BakedModelManifest> manifests) {
        public static final Result EMPTY = new Result(Map.of(), Map.of(), Map.of(), Map.of());
    }

    public static Result scan(Path configDir, long stamp) {
        if (configDir == null || !Files.isDirectory(configDir)) return Result.EMPTY;

        Map<Identifier, BakedModel> models = loadModels(
                configDir.resolve(BedrockResourceLoader.MODEL_PATH_PREFIX), stamp);
        Map<Identifier, BakedAnimation> animations = loadAnimations(
                configDir.resolve(BedrockResourceLoader.ANIMATION_PATH_PREFIX), models);
        Map<Identifier, BakedRenderController> renderControllers = loadRenderControllers(
                configDir.resolve(BedrockResourceLoader.RENDER_CONTROLLER_PATH_PREFIX), stamp);
        Map<Identifier, BakedModelManifest> manifests = loadModelManifests(
                configDir.resolve(BedrockResourceLoader.MODEL_MANIFEST_PATH_PREFIX), stamp);
        return new Result(models, animations, renderControllers, manifests);
    }

    /** Stats returned by {@link #rescan} — used by the GUI to show a confirmation toast. */
    public record RescanStats(int models, int animations, int renderControllers, int manifests) {
        public static final RescanStats EMPTY = new RescanStats(0, 0, 0, 0);
    }

    /**
     * Re-scan {@code configDir} and atomically replace the {@code animus_user}
     * namespace in all three baked-asset libraries — vanilla {@code animus}
     * entries stay untouched. Used by the model-chooser GUI's "refresh"
     * button so authors iterating on custom models don't have to trigger a
     * full ResourceManager reload.
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
                result.models().size(), result.animations().size(), result.renderControllers().size(),
                result.manifests().size(), stamp);
        return new RescanStats(result.models().size(), result.animations().size(),
                result.renderControllers().size(), result.manifests().size());
    }

    private static Map<Identifier, BakedModel> loadModels(Path modelsDir, long stamp) {
        Map<Identifier, BakedModel> out = new HashMap<>();
        if (!Files.isDirectory(modelsDir)) return out;
        try (Stream<Path> walk = Files.walk(modelsDir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(BedrockResourceLoader.JSON_EXTENSION))
                .filter(Files::isRegularFile)
                .forEach(p -> {
                    String shortName = stripJsonExt(modelsDir.relativize(p).toString().replace('\\', '/'));
                    Identifier key = Identifier.fromNamespaceAndPath(CONFIG_NAMESPACE, shortName);
                    try (BufferedReader r = Files.newBufferedReader(p)) {
                        BedrockGeoFile file = GSON.fromJson(r, BedrockGeoFile.class);
                        out.put(key, ModelBaker.bake(file, stamp));
                    } catch (Exception ex) {
                        Constants.LOG.error("[animus-anim] failed to load config geo {}: {}", p, ex.toString());
                    }
                });
        } catch (IOException ex) {
            Constants.LOG.error("[animus-anim] failed to walk config models dir {}: {}", modelsDir, ex.toString());
        }
        return out;
    }

    private static Map<Identifier, BakedAnimation> loadAnimations(Path animDir,
                                                                   Map<Identifier, BakedModel> models) {
        Map<Identifier, BakedAnimation> out = new HashMap<>();
        if (!Files.isDirectory(animDir)) return out;
        try (Stream<Path> walk = Files.walk(animDir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(BedrockResourceLoader.JSON_EXTENSION))
                .filter(Files::isRegularFile)
                .forEach(p -> {
                    String shortName = stripJsonExt(animDir.relativize(p).toString().replace('\\', '/'));
                    Identifier modelKey = Identifier.fromNamespaceAndPath(CONFIG_NAMESPACE, shortName);
                    BakedModel model = models.get(modelKey);
                    if (model == null) {
                        Constants.LOG.warn("[animus-anim] config animation {} has no matching model {} — skipping",
                                p, modelKey);
                        return;
                    }
                    try (BufferedReader r = Files.newBufferedReader(p)) {
                        JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                        Map<String, BakedAnimation> anims = AnimationBaker.bake(root, model);
                        for (Map.Entry<String, BakedAnimation> a : anims.entrySet()) {
                            Identifier id = Identifier.fromNamespaceAndPath(CONFIG_NAMESPACE,
                                    modelKey.getPath() + "/" + a.getKey());
                            out.put(id, a.getValue());
                        }
                    } catch (Exception ex) {
                        Constants.LOG.error("[animus-anim] failed to load config animations {}: {}", p, ex.toString());
                    }
                });
        } catch (IOException ex) {
            Constants.LOG.error("[animus-anim] failed to walk config animations dir {}: {}", animDir, ex.toString());
        }
        return out;
    }

    private static Map<Identifier, BakedModelManifest> loadModelManifests(Path manifestDir, long stamp) {
        Map<Identifier, BakedModelManifest> out = new HashMap<>();
        if (!Files.isDirectory(manifestDir)) return out;
        try (Stream<Path> walk = Files.walk(manifestDir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(BedrockResourceLoader.JSON_EXTENSION))
                .filter(Files::isRegularFile)
                .forEach(p -> {
                    String shortName = stripJsonExt(manifestDir.relativize(p).toString().replace('\\', '/'));
                    Identifier key = Identifier.fromNamespaceAndPath(CONFIG_NAMESPACE, shortName);
                    try (BufferedReader r = Files.newBufferedReader(p)) {
                        BedrockModelManifest file = GSON.fromJson(r, BedrockModelManifest.class);
                        out.put(key, ModelManifestBaker.bake(file, stamp));
                    } catch (Exception ex) {
                        Constants.LOG.error("[animus-anim] failed to load config model_manifest {}: {}", p, ex.toString());
                    }
                });
        } catch (IOException ex) {
            Constants.LOG.error("[animus-anim] failed to walk config model_manifests dir {}: {}", manifestDir, ex.toString());
        }
        return out;
    }

    private static Map<Identifier, BakedRenderController> loadRenderControllers(Path rcDir, long stamp) {
        Map<Identifier, BakedRenderController> out = new HashMap<>();
        if (!Files.isDirectory(rcDir)) return out;
        try (Stream<Path> walk = Files.walk(rcDir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(BedrockResourceLoader.JSON_EXTENSION))
                .filter(Files::isRegularFile)
                .forEach(p -> {
                    String shortName = stripJsonExt(rcDir.relativize(p).toString().replace('\\', '/'));
                    Identifier key = Identifier.fromNamespaceAndPath(CONFIG_NAMESPACE, shortName);
                    try (BufferedReader r = Files.newBufferedReader(p)) {
                        BedrockRenderControllerFile file = GSON.fromJson(r, BedrockRenderControllerFile.class);
                        BakedRenderController controller = RenderControllerBaker.bake(file, stamp);
                        if (controller != null) out.put(key, controller);
                    } catch (Exception ex) {
                        Constants.LOG.error("[animus-anim] failed to load config render_controller {}: {}", p, ex.toString());
                    }
                });
        } catch (IOException ex) {
            Constants.LOG.error("[animus-anim] failed to walk config render_controllers dir {}: {}", rcDir, ex.toString());
        }
        return out;
    }

    private static String stripJsonExt(String s) {
        return s.endsWith(BedrockResourceLoader.JSON_EXTENSION)
                ? s.substring(0, s.length() - BedrockResourceLoader.JSON_EXTENSION.length())
                : s;
    }
}
