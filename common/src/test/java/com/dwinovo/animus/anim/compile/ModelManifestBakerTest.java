package com.dwinovo.animus.anim.compile;

import com.dwinovo.animus.anim.baked.BakedModelManifest;
import com.dwinovo.animus.anim.format.BedrockModelManifest;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelManifestBakerTest {

    private static final Gson GSON = new Gson();

    private static BakedModelManifest bake(String json) {
        BedrockModelManifest src = GSON.fromJson(json, BedrockModelManifest.class);
        return ModelManifestBaker.bake(src, 1L);
    }

    @Test
    void emptyManifestProducesAllNulls() {
        BakedModelManifest baked = bake("{}");
        assertNull(baked.displayName());
        assertNull(baked.displayNameKey());
        assertNull(baked.description());
        assertNull(baked.descriptionKey());
        assertNull(baked.author());
    }

    @Test
    void nullSourceProducesEmpty() {
        BakedModelManifest baked = ModelManifestBaker.bake(null, 7L);
        assertNotNull(baked);
        assertNull(baked.displayName());
        assertEquals(7L, baked.bakeStamp());
    }

    @Test
    void hachiwareManifestRoundtrip() {
        BakedModelManifest baked = bake("""
                {
                  "format_version": "1.0.0",
                  "schema_version": "animus:1.0",
                  "display_name_key": "animus.model.hachiware.name",
                  "description_key": "animus.model.hachiware.description",
                  "author": "dwinovo"
                }
                """);
        assertEquals("animus.model.hachiware.name", baked.displayNameKey());
        assertEquals("animus.model.hachiware.description", baked.descriptionKey());
        assertEquals("dwinovo", baked.author());
        assertNull(baked.displayName());  // not provided, lang-key takes precedence
    }

    @Test
    void playerManifestUsesLiteralDisplayName() {
        // Player-supplied models typically don't ship a lang file, so they
        // populate display_name / description directly.
        BakedModelManifest baked = bake("""
                {
                  "schema_version": "animus:1.0",
                  "display_name": "My Custom Skin",
                  "description": "A reskin of the default model.",
                  "author": "player_one"
                }
                """);
        assertEquals("My Custom Skin", baked.displayName());
        assertEquals("A reskin of the default model.", baked.description());
        assertEquals("player_one", baked.author());
        assertNull(baked.displayNameKey());
    }

    @Test
    void blankStringsBecomeNull() {
        // Blank strings should be treated as "not provided" so the GUI's
        // fallback chain (lang-key → literal → id) works uniformly.
        BakedModelManifest baked = bake("""
                {
                  "schema_version": "animus:1.0",
                  "display_name": "",
                  "display_name_key": "   ",
                  "author": "  "
                }
                """);
        assertNull(baked.displayName());
        assertNull(baked.displayNameKey());
        assertNull(baked.author());
    }

    @Test
    void bakeStampIsPreserved() {
        BakedModelManifest baked = bake("{\"schema_version\": \"animus:1.0\"}");
        // Using a custom stamp value rather than just the default
        BedrockModelManifest src = GSON.fromJson("{\"schema_version\": \"animus:1.0\"}", BedrockModelManifest.class);
        BakedModelManifest withCustomStamp = ModelManifestBaker.bake(src, 999L);
        assertEquals(999L, withCustomStamp.bakeStamp());
    }
}
