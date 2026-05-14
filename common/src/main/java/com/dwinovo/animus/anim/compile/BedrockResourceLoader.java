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
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reload-aware loader that walks the resource tree, decodes Bedrock geo /
 * animation JSON, and replaces the contents of the model / animation
 * libraries.
 *
 * <p>Animations live at {@code assets/<ns>/animations/<file>.json} and are
 * baked against the model with the matching short key. Each animation in the
 * file is registered under {@code <ns>:<file>/<anim_name>} (e.g.
 * {@code animus:hachiware/idle}). Decorative animations like {@code blink}
 * and {@code breath} are authored in the same animation file, then wired into
 * controllers in code via {@code AnimusEntityRenderer.addLoopingController}.
 *
 * <h2>Dual source</h2>
 * In addition to the vanilla resource manager (which feeds the {@code animus}
 * namespace from {@code assets/animus/}), the loader can also pull models /
 * animations / textures from {@code <gameDir>/config/animus/models/} into the
 * {@code animus_user} namespace via {@link ConfigModelLoader} +
 * {@link ConfigTextureLoader}. Pass the config directory to the constructor
 * to enable; pass {@code null} (or use the no-arg ctor) to disable.
 */
public final class BedrockResourceLoader implements ResourceManagerReloadListener {

    public static final String MODEL_PATH_PREFIX = "models/entity";
    public static final String ANIMATION_PATH_PREFIX = "animations";
    public static final String RENDER_CONTROLLER_PATH_PREFIX = "render_controllers";
    public static final String MODEL_MANIFEST_PATH_PREFIX = "model_manifests";
    public static final String JSON_EXTENSION = ".json";

    private static final Gson GSON = new Gson();

    private final @Nullable Path configDir;

    public BedrockResourceLoader() {
        this(null);
    }

    public BedrockResourceLoader(@Nullable Path configDir) {
        this.configDir = configDir;
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        // Bump the bake stamp so any baked object that survives across the reload
        // is detectably stale via stamp comparison.
        long stamp = BakeStamp.next();

        Map<Identifier, BakedModel> models = new HashMap<>(loadModels(manager, stamp));
        Map<Identifier, BakedAnimation> animations = new HashMap<>(loadAnimations(manager, models));
        Map<Identifier, BakedRenderController> renderControllers = new HashMap<>(loadRenderControllers(manager, stamp));
        Map<Identifier, BakedModelManifest> manifests = new HashMap<>(loadModelManifests(manager, stamp));

        int vanillaModelCount = models.size();
        int vanillaAnimCount = animations.size();
        int vanillaRcCount = renderControllers.size();
        int vanillaManifestCount = manifests.size();

        if (configDir != null) {
            ConfigModelLoader.Result configResult = ConfigModelLoader.scan(configDir, stamp);
            models.putAll(configResult.models());
            animations.putAll(configResult.animations());
            renderControllers.putAll(configResult.renderControllers());
            manifests.putAll(configResult.manifests());
            ConfigTextureLoader.scan(configDir);
        }

        ModelLibrary.replaceAll(models);
        AnimationLibrary.replaceAll(animations);
        RenderControllerLibrary.replaceAll(renderControllers);
        ModelManifestLibrary.replaceAll(manifests);

        int userModelCount = models.size() - vanillaModelCount;
        int userAnimCount = animations.size() - vanillaAnimCount;
        int userRcCount = renderControllers.size() - vanillaRcCount;
        int userManifestCount = manifests.size() - vanillaManifestCount;
        Constants.LOG.info("[animus-anim] loaded {} baked models ({} from assets, {} from config) (stamp {})",
                models.size(), vanillaModelCount, userModelCount, stamp);
        Constants.LOG.info("[animus-anim] loaded {} baked animations ({} from assets, {} from config) (stamp {})",
                animations.size(), vanillaAnimCount, userAnimCount, stamp);
        Constants.LOG.info("[animus-anim] loaded {} render controllers ({} from assets, {} from config) (stamp {})",
                renderControllers.size(), vanillaRcCount, userRcCount, stamp);
        Constants.LOG.info("[animus-anim] loaded {} model manifests ({} from assets, {} from config) (stamp {})",
                manifests.size(), vanillaManifestCount, userManifestCount, stamp);
    }

    private static Map<Identifier, BakedModel> loadModels(ResourceManager manager, long stamp) {
        Map<Identifier, BakedModel> baked = new HashMap<>();
        Map<Identifier, Resource> resources = manager.listResources(MODEL_PATH_PREFIX,
                id -> id.getPath().endsWith(JSON_EXTENSION));
        for (Map.Entry<Identifier, Resource> e : resources.entrySet()) {
            Identifier rid = e.getKey();
            Identifier modelKey = toModelKey(rid);
            try (BufferedReader reader = e.getValue().openAsReader()) {
                BedrockGeoFile file = GSON.fromJson(reader, BedrockGeoFile.class);
                BakedModel model = ModelBaker.bake(file, stamp);
                baked.put(modelKey, model);
            } catch (Exception ex) {
                Constants.LOG.error("[animus-anim] failed to load geo {}: {}", rid, ex.toString());
            }
        }
        return baked;
    }

    private static Map<Identifier, BakedAnimation> loadAnimations(ResourceManager manager,
                                                                  Map<Identifier, BakedModel> models) {
        Map<Identifier, BakedAnimation> baked = new HashMap<>();
        Map<Identifier, Resource> resources = manager.listResources(ANIMATION_PATH_PREFIX,
                id -> id.getPath().endsWith(JSON_EXTENSION));
        for (Map.Entry<Identifier, Resource> e : resources.entrySet()) {
            Identifier rid = e.getKey();
            Identifier modelKey = toAnimationFileKey(rid);
            BakedModel model = models.get(modelKey);
            if (model == null) {
                Constants.LOG.warn("[animus-anim] animation file {} has no matching model {} — skipping",
                        rid, modelKey);
                continue;
            }
            try (BufferedReader reader = e.getValue().openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                Map<String, BakedAnimation> anims = AnimationBaker.bake(root, model);
                for (Map.Entry<String, BakedAnimation> a : anims.entrySet()) {
                    Identifier id = Identifier.fromNamespaceAndPath(modelKey.getNamespace(),
                            modelKey.getPath() + "/" + a.getKey());
                    baked.put(id, a.getValue());
                }
            } catch (Exception ex) {
                Constants.LOG.error("[animus-anim] failed to load animations {}: {}", rid, ex.toString());
            }
        }
        return baked;
    }

    private static Map<Identifier, BakedModelManifest> loadModelManifests(ResourceManager manager, long stamp) {
        Map<Identifier, BakedModelManifest> baked = new HashMap<>();
        Map<Identifier, Resource> resources = manager.listResources(MODEL_MANIFEST_PATH_PREFIX,
                id -> id.getPath().endsWith(JSON_EXTENSION));
        for (Map.Entry<Identifier, Resource> e : resources.entrySet()) {
            Identifier rid = e.getKey();
            Identifier manifestKey = toModelManifestKey(rid);
            try (BufferedReader reader = e.getValue().openAsReader()) {
                BedrockModelManifest file = GSON.fromJson(reader, BedrockModelManifest.class);
                baked.put(manifestKey, ModelManifestBaker.bake(file, stamp));
            } catch (Exception ex) {
                Constants.LOG.error("[animus-anim] failed to load model_manifest {}: {}", rid, ex.toString());
            }
        }
        return baked;
    }

    private static Map<Identifier, BakedRenderController> loadRenderControllers(ResourceManager manager, long stamp) {
        Map<Identifier, BakedRenderController> baked = new HashMap<>();
        Map<Identifier, Resource> resources = manager.listResources(RENDER_CONTROLLER_PATH_PREFIX,
                id -> id.getPath().endsWith(JSON_EXTENSION));
        for (Map.Entry<Identifier, Resource> e : resources.entrySet()) {
            Identifier rid = e.getKey();
            Identifier rcKey = toRenderControllerKey(rid);
            try (BufferedReader reader = e.getValue().openAsReader()) {
                BedrockRenderControllerFile file = GSON.fromJson(reader, BedrockRenderControllerFile.class);
                BakedRenderController controller = RenderControllerBaker.bake(file, stamp);
                if (controller != null) baked.put(rcKey, controller);
            } catch (Exception ex) {
                Constants.LOG.error("[animus-anim] failed to load render_controller {}: {}", rid, ex.toString());
            }
        }
        return baked;
    }

    /** Strips {@value #MODEL_PATH_PREFIX}/ prefix and .json suffix. */
    public static Identifier toModelKey(Identifier resourceId) {
        return stripPrefixAndExt(resourceId, MODEL_PATH_PREFIX);
    }

    /** Strips {@value #ANIMATION_PATH_PREFIX}/ prefix and .json suffix. */
    public static Identifier toAnimationFileKey(Identifier resourceId) {
        return stripPrefixAndExt(resourceId, ANIMATION_PATH_PREFIX);
    }

    /** Strips {@value #RENDER_CONTROLLER_PATH_PREFIX}/ prefix and .json suffix. */
    public static Identifier toRenderControllerKey(Identifier resourceId) {
        return stripPrefixAndExt(resourceId, RENDER_CONTROLLER_PATH_PREFIX);
    }

    /** Strips {@value #MODEL_MANIFEST_PATH_PREFIX}/ prefix and .json suffix. */
    public static Identifier toModelManifestKey(Identifier resourceId) {
        return stripPrefixAndExt(resourceId, MODEL_MANIFEST_PATH_PREFIX);
    }

    private static Identifier stripPrefixAndExt(Identifier resourceId, String prefix) {
        String path = resourceId.getPath();
        if (path.startsWith(prefix + "/")) {
            path = path.substring(prefix.length() + 1);
        }
        if (path.endsWith(JSON_EXTENSION)) {
            path = path.substring(0, path.length() - JSON_EXTENSION.length());
        }
        return Identifier.fromNamespaceAndPath(resourceId.getNamespace(), path);
    }
}
