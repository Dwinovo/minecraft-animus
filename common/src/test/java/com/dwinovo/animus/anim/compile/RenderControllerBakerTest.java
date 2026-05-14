package com.dwinovo.animus.anim.compile;

import com.dwinovo.animus.anim.baked.BakedRenderController;
import com.dwinovo.animus.anim.format.BedrockRenderControllerFile;
import com.dwinovo.animus.anim.molang.MolangContext;
import com.dwinovo.animus.anim.molang.MolangNode;
import com.dwinovo.animus.anim.molang.MolangQueries;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderControllerBakerTest {

    private static final Gson GSON = new Gson();

    private static BakedRenderController bake(String json) {
        BedrockRenderControllerFile file = GSON.fromJson(json, BedrockRenderControllerFile.class);
        return RenderControllerBaker.bake(file, 1L);
    }

    @Test
    void emptyFileReturnsNull() {
        assertEquals(null, RenderControllerBaker.bake(null, 1L));
    }

    @Test
    void boolValuesCompileToConst() {
        BakedRenderController rc = bake("""
                {
                  "format_version": "1.8.0",
                  "render_controllers": {
                    "controller.render.test": {
                      "part_visibility": [
                        { "*": true },
                        { "guitar": false }
                      ]
                    }
                  }
                }
                """);
        assertNotNull(rc);
        assertEquals(2, rc.rules.size());
        assertEquals(MolangNode.Const.class, rc.rules.get(0).expression().getClass());
        assertEquals(MolangNode.Const.class, rc.rules.get(1).expression().getClass());
    }

    @Test
    void molangExpressionCompilesToCmp() {
        BakedRenderController rc = bake("""
                {
                  "format_version": "1.8.0",
                  "render_controllers": {
                    "controller.render.test": {
                      "part_visibility": [
                        { "guitar": "query.task == 'play_music'" }
                      ]
                    }
                  }
                }
                """);
        assertNotNull(rc);
        assertEquals(1, rc.rules.size());
        assertEquals("guitar", rc.rules.get(0).boneGlob());
        assertEquals(MolangNode.Cmp.class, rc.rules.get(0).expression().getClass());
    }

    @Test
    void hiddenForReflectsTask() {
        BakedRenderController rc = bake("""
                {
                  "format_version": "1.8.0",
                  "render_controllers": {
                    "controller.render.test": {
                      "part_visibility": [
                        { "guitar": "query.task == 'play_music'" }
                      ]
                    }
                  }
                }
                """);
        assertNotNull(rc);
        BakedRenderController.Rule rule = rc.rules.get(0);
        MolangContext ctx = new MolangContext();

        // stub task is 'none' — guitar should be hidden
        ctx.querySlots[MolangQueries.QUERY_TASK] = MolangQueries.hashString("none");
        assertTrue(rule.isHiddenFor(ctx));

        // task switches to play_music — guitar should be visible
        ctx.querySlots[MolangQueries.QUERY_TASK] = MolangQueries.hashString("play_music");
        assertEquals(false, rule.isHiddenFor(ctx));
    }

    @Test
    void invalidGlobsAreSkipped() {
        BakedRenderController rc = bake("""
                {
                  "format_version": "1.8.0",
                  "render_controllers": {
                    "controller.render.test": {
                      "part_visibility": [
                        { "a*b": true },
                        { "wing_*": true }
                      ]
                    }
                  }
                }
                """);
        assertNotNull(rc);
        // a*b is invalid (mid-wildcard) and dropped; wing_* is kept.
        assertEquals(1, rc.rules.size());
        assertEquals("wing_*", rc.rules.get(0).boneGlob());
    }

    @Test
    void hachiwareDemoShapeRoundtrip() {
        // Mirrors assets/animus/render_controllers/hachiware.json
        BakedRenderController rc = bake("""
                {
                  "format_version": "1.8.0",
                  "render_controllers": {
                    "controller.render.hachiware": {
                      "geometry": "Geometry.default",
                      "materials": [{ "*": "Material.default" }],
                      "textures": ["Texture.default"],
                      "part_visibility": [
                        { "*": true },
                        { "guitar": "query.task == 'play_music'" },
                        { "Mouth3": "query.task == 'play_music'" },
                        { "RightHandLocator": "query.task != 'play_music'" }
                      ]
                    }
                  }
                }
                """);
        assertNotNull(rc);
        assertEquals(4, rc.rules.size());
        assertEquals("*", rc.rules.get(0).boneGlob());
        assertEquals("guitar", rc.rules.get(1).boneGlob());
        assertEquals("Mouth3", rc.rules.get(2).boneGlob());
        assertEquals("RightHandLocator", rc.rules.get(3).boneGlob());
    }
}
