package com.dwinovo.animus.anim.render;

import com.dwinovo.animus.anim.baked.BakedModel;
import com.dwinovo.animus.anim.runtime.AnimContext;

import java.util.List;
import java.util.Map;

/**
 * Pure-function helpers for evaluating {@link BoneVisibilityRule}s against
 * a model. Extracted out of {@link AnimusEntityRenderer} so the logic is
 * unit-testable without spinning up a Minecraft entity renderer.
 *
 * <h2>Semantics recap</h2>
 * Bones with no rule registered are <em>visible</em>. A bone with one or
 * more rules is visible when <em>any</em> rule votes {@code true} (OR
 * across rules). The output array is parallel to {@link BakedModel#bones}
 * and holds {@code true} for each bone that should be skipped during
 * rendering.
 *
 * <h2>Layering with molang render controllers</h2>
 * Java-lambda rules are applied first (OR semantics within), then a
 * Bedrock {@code part_visibility}–derived
 * {@link com.dwinovo.animus.anim.baked.BakedRenderController} may overwrite
 * the same {@code hidden[]} array (last-write-wins per Bedrock). Callers
 * should run both layers against the same array to compose them.
 */
public final class BoneVisibility {

    private BoneVisibility() {}

    /**
     * Evaluate every registered Java-lambda rule against the given state,
     * writing the result into {@code hidden[]} in place.
     *
     * @param rules  registered rules keyed by bone name
     * @param model  the entity's resolved baked model — needed to translate
     *               bone names to indices
     * @param state  per-frame render-state snapshot
     * @param ctx    gameplay-state snapshot consumed by rule lambdas
     * @param hidden output array, parallel to {@code model.bones}. Modified
     *               in place — {@code true} means hidden.
     */
    public static void applyJavaRules(Map<String, List<BoneVisibilityRule>> rules,
                                      BakedModel model,
                                      AnimusRenderState state,
                                      AnimContext ctx,
                                      boolean[] hidden) {
        if (rules == null || rules.isEmpty() || model == null) return;
        for (Map.Entry<String, List<BoneVisibilityRule>> entry : rules.entrySet()) {
            Integer boneIdx = model.boneIndex.get(entry.getKey());
            if (boneIdx == null) continue; // rule for a bone the model lacks — silent skip
            boolean anyVisible = false;
            for (BoneVisibilityRule rule : entry.getValue()) {
                if (rule.isVisible(state, ctx)) {
                    anyVisible = true;
                    break;
                }
            }
            hidden[boneIdx] = !anyVisible;
        }
    }
}
