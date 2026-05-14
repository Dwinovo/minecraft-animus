package com.dwinovo.animus.anim.compile;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.anim.baked.BakedModelManifest;
import com.dwinovo.animus.anim.format.BedrockModelManifest;

/**
 * Bakes a parsed {@link BedrockModelManifest} into a {@link BakedModelManifest},
 * validating the schema version and stripping blank strings to {@code null}
 * so the GUI's fallback logic has a single contract: {@code null} means
 * "not provided".
 */
public final class ModelManifestBaker {

    /** Currently accepted schema. Bump and add a migration branch when the schema evolves. */
    public static final String CURRENT_SCHEMA = "animus:1.0";

    private ModelManifestBaker() {}

    public static BakedModelManifest bake(BedrockModelManifest src, long stamp) {
        if (src == null) return BakedModelManifest.empty(stamp);

        if (src.schemaVersion != null && !CURRENT_SCHEMA.equals(src.schemaVersion)) {
            Constants.LOG.warn("[animus-anim] model manifest schema_version '{}' is not '{}'; using anyway",
                    src.schemaVersion, CURRENT_SCHEMA);
        }
        return new BakedModelManifest(
                blankToNull(src.displayNameKey),
                blankToNull(src.displayName),
                blankToNull(src.descriptionKey),
                blankToNull(src.description),
                blankToNull(src.author),
                stamp);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
