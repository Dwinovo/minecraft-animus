package com.dwinovo.animus.anim.api;

import com.dwinovo.animus.anim.baked.BakedAnimation;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of baked animations, keyed by
 * {@code <namespace>:<file>/<anim_name>}. Populated by
 * {@link com.dwinovo.animus.anim.compile.BedrockResourceLoader} during
 * resource reload, read by entity renderers / animators each frame.
 *
 * <p>Map is replaced atomically on reload, so callers can safely cache the
 * snapshot they obtained for the duration of a single frame.
 */
public final class AnimationLibrary {

    private static volatile Map<Identifier, BakedAnimation> animations = Map.of();

    private AnimationLibrary() {}

    public static BakedAnimation get(Identifier id) {
        return animations.get(id);
    }

    /** Replaces the registry contents. Called by the resource loader. */
    public static void replaceAll(Map<Identifier, BakedAnimation> next) {
        animations = new ConcurrentHashMap<>(next);
    }

    /**
     * Replace entries belonging to a single namespace. See
     * {@link ModelLibrary#replaceNamespace} for the rationale — the refresh
     * button rescans only the player's config directory.
     */
    public static void replaceNamespace(String namespace, Map<Identifier, BakedAnimation> entries) {
        Map<Identifier, BakedAnimation> next = new ConcurrentHashMap<>(animations);
        next.keySet().removeIf(id -> namespace.equals(id.getNamespace()));
        next.putAll(entries);
        animations = next;
    }
}
