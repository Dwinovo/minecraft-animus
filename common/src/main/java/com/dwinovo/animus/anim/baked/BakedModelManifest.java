package com.dwinovo.animus.anim.baked;

import org.jspecify.annotations.Nullable;

/**
 * Immutable baked form of {@code model_manifests/<id>.json}. Fields are all
 * nullable so {@code null} means "not provided" — the GUI is responsible
 * for falling back gracefully when display metadata is missing.
 *
 * <p>See {@link com.dwinovo.animus.anim.format.BedrockModelManifest} for the
 * source schema and display-name resolution priority.
 */
public record BakedModelManifest(
        @Nullable String displayNameKey,
        @Nullable String displayName,
        @Nullable String descriptionKey,
        @Nullable String description,
        @Nullable String author,
        long bakeStamp) {

    /** Empty manifest — used as the fallback when a model has no manifest file. */
    public static BakedModelManifest empty(long bakeStamp) {
        return new BakedModelManifest(null, null, null, null, null, bakeStamp);
    }
}
