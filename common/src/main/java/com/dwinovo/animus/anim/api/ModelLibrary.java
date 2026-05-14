package com.dwinovo.animus.anim.api;

import com.dwinovo.animus.anim.baked.BakedModel;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of baked models, keyed by mod-defined identifier.
 * Populated by {@link com.dwinovo.animus.anim.compile.BedrockResourceLoader}
 * during resource reload, read by entity renderers each frame.
 *
 * <p>Map is replaced atomically on reload, so renderers can safely cache the
 * snapshot they were given for the duration of a single frame.
 */
public final class ModelLibrary {

    private static volatile Map<Identifier, BakedModel> models = Map.of();

    private ModelLibrary() {}

    public static BakedModel get(Identifier id) {
        return models.get(id);
    }

    /** Replaces the registry contents. Called by the resource loader. */
    public static void replaceAll(Map<Identifier, BakedModel> next) {
        // Defensive copy into a concurrent map so iteration is safe; assignment is atomic.
        Map<Identifier, BakedModel> snapshot = new ConcurrentHashMap<>(next);
        models = snapshot;
    }
}
