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

    /**
     * Replace entries belonging to a single namespace. See
     * {@link ModelLibrary#replaceNamespace} for the rationale — the refresh
     * button rescans only the player's config directory.
     */
    public static void replaceNamespace(String namespace, Map<Identifier, BakedRenderController> entries) {
        Map<Identifier, BakedRenderController> next = new ConcurrentHashMap<>(controllers);
        next.keySet().removeIf(id -> namespace.equals(id.getNamespace()));
        next.putAll(entries);
        controllers = next;
    }
}
