package com.dwinovo.animus.anim.baked;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BakedRenderControllerTest {

    @Test
    void globExactMatch() {
        assertTrue(BakedRenderController.matchesGlob("guitar", "guitar"));
        assertFalse(BakedRenderController.matchesGlob("guitar", "Guitar"));
        assertFalse(BakedRenderController.matchesGlob("guitar", "guita"));
    }

    @Test
    void globStarMatchesAll() {
        assertTrue(BakedRenderController.matchesGlob("*", "anything"));
        assertTrue(BakedRenderController.matchesGlob("*", ""));
        assertTrue(BakedRenderController.matchesGlob("*", "Root"));
    }

    @Test
    void globPrefix() {
        assertTrue(BakedRenderController.matchesGlob("wing_*", "wing_left"));
        assertTrue(BakedRenderController.matchesGlob("wing_*", "wing_"));
        assertFalse(BakedRenderController.matchesGlob("wing_*", "leg_left"));
        assertFalse(BakedRenderController.matchesGlob("wing_*", "wing"));
    }

    @Test
    void globSuffix() {
        assertTrue(BakedRenderController.matchesGlob("*_locator", "right_locator"));
        assertTrue(BakedRenderController.matchesGlob("*_locator", "_locator"));
        assertFalse(BakedRenderController.matchesGlob("*_locator", "locator_right"));
    }

    @Test
    void globMiddleWildcardUnsupported() {
        // RenderControllerBaker rejects these at compile, but the runtime
        // matcher also returns false defensively.
        assertFalse(BakedRenderController.matchesGlob("a*b", "axb"));
    }
}
