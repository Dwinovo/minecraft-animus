package com.dwinovo.animus.anim.format;

import com.google.gson.annotations.SerializedName;

/**
 * Gson deserialization target for {@code model_manifests/<id>.json} — the
 * mod-specific metadata sidecar that describes a model to the model-chooser
 * GUI (display name, description, author). Geometry / animation / texture
 * stay in their own files; this one is purely descriptive.
 *
 * <h2>Schema</h2>
 * <pre>
 * {
 *   "format_version": "1.0.0",
 *   "schema_version": "animus:1.0",
 *   "display_name_key": "animus.model.hachiware.name",
 *   "description_key": "animus.model.hachiware.description",
 *   "display_name": "Hachiware",
 *   "description": "Default model — a small black-and-white feline character.",
 *   "author": "dwinovo"
 * }
 * </pre>
 *
 * <h2>Display name resolution priority</h2>
 * <ol>
 *   <li>{@link #displayNameKey} — translatable component, preferred for mod
 *       built-ins so users see their locale.</li>
 *   <li>{@link #displayName} — literal string, used for player-supplied
 *       models that don't ship a lang file.</li>
 *   <li>Fall back to the model id (e.g. {@code animus_user:my_skin}).</li>
 * </ol>
 *
 * <h2>schema_version</h2>
 * Format identifier in the shape {@code animus:N.M}. Currently only
 * {@code animus:1.0} is accepted; future revisions bump it and the baker
 * warns + skips manifests with unknown values. Lets us evolve the file
 * format without breaking older packs silently.
 */
public final class BedrockModelManifest {

    @SerializedName("format_version")
    public String formatVersion;

    @SerializedName("schema_version")
    public String schemaVersion;

    @SerializedName("display_name_key")
    public String displayNameKey;

    @SerializedName("description_key")
    public String descriptionKey;

    @SerializedName("display_name")
    public String displayName;

    public String description;

    public String author;
}
