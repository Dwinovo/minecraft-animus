package com.dwinovo.animus.anim.api;

import com.dwinovo.animus.anim.baked.BakedModelManifest;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of baked model manifests, keyed by the same
 * identifier as {@link ModelLibrary} (e.g. {@code animus:hachiware} or
 * {@code animus_user:my_skin}). The {@code ChooseModelScreen} reads display
 * metadata from here when rendering the model list.
 *
 * <p>Models without a manifest file simply don't have an entry here — the
 * GUI falls back to the raw identifier as the display name.
 */
public final class ModelManifestLibrary {

    private static volatile Map<Identifier, BakedModelManifest> manifests = Map.of();

    private ModelManifestLibrary() {}

    public static BakedModelManifest get(Identifier id) {
        return manifests.get(id);
    }

    public static void replaceAll(Map<Identifier, BakedModelManifest> next) {
        manifests = new ConcurrentHashMap<>(next);
    }

    /**
     * Replace entries belonging to a single namespace. See
     * {@link ModelLibrary#replaceNamespace} for the rationale — the refresh
     * button rescans only the player's config directory.
     */
    public static void replaceNamespace(String namespace, Map<Identifier, BakedModelManifest> entries) {
        Map<Identifier, BakedModelManifest> next = new ConcurrentHashMap<>(manifests);
        next.keySet().removeIf(id -> namespace.equals(id.getNamespace()));
        next.putAll(entries);
        manifests = next;
    }
}
