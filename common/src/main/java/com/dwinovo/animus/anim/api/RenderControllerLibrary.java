package com.dwinovo.animus.anim.api;

import com.dwinovo.animus.anim.baked.BakedRenderController;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of baked render controllers, keyed by
 * {@code <namespace>:<file-stem>} (e.g. {@code animus:hachiware}). Populated
 * by {@link com.dwinovo.animus.anim.compile.BedrockResourceLoader} during
 * resource reload and by {@link com.dwinovo.animus.anim.compile.ConfigModelLoader}
 * for player-supplied controllers in the {@code animus_user} namespace.
 *
 * <p>Map is replaced atomically on reload, so renderers can safely cache the
 * snapshot they were given for the duration of a single frame.
 */
public final class RenderControllerLibrary {

    private static volatile Map<Identifier, BakedRenderController> controllers = Map.of();

    private RenderControllerLibrary() {}

    public static BakedRenderController get(Identifier id) {
        return controllers.get(id);
    }

    /** Replaces the registry contents. Called by the resource loader. */
    public static void replaceAll(Map<Identifier, BakedRenderController> next) {
        controllers = new ConcurrentHashMap<>(next);
    }
}
