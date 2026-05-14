package com.dwinovo.animus.anim.compile;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.anim.baked.BakedRenderController;
import com.dwinovo.animus.anim.format.BedrockRenderControllerFile;
import com.dwinovo.animus.anim.molang.MolangNode;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bakes a parsed {@link BedrockRenderControllerFile} into a
 * {@link BakedRenderController} — compiling each {@code part_visibility}
 * entry's Molang expression into a {@link MolangNode} so evaluation at
 * render time is allocation-free.
 *
 * <h2>Per-file convention</h2>
 * Each Bedrock {@code render_controllers.json} can declare multiple controllers
 * under {@code render_controllers.<name>}. Animus consumes <em>only the first</em>
 * controller in the file — multi-controller resource packs aren't supported in
 * this phase, and the entity-to-controller binding is one-to-one. The file's
 * stem (e.g. {@code hachiware.json} → {@code "hachiware"}) becomes the lookup
 * key, not the {@code controller.render.*} name inside the file.
 *
 * <h2>JSON-bool optimisation</h2>
 * Entries like {@code "guitar": true} or {@code "*": false} compile to
 * {@code Const(1.0)} / {@code Const(0.0)} directly — no Molang parsing.
 *
 * <h2>Bone glob validation</h2>
 * Patterns with multiple wildcards or mid-string wildcards are warned and
 * skipped. Only {@code *}, {@code prefix*}, {@code *suffix}, and exact names
 * are supported (see {@link BakedRenderController#matchesGlob}).
 */
public final class RenderControllerBaker {

    private RenderControllerBaker() {}

    /**
     * @return baked controller, or {@code null} if the file has no usable
     *         controllers (empty map, no part_visibility, all rules unparseable).
     */
    public static BakedRenderController bake(BedrockRenderControllerFile file, long stamp) {
        if (file == null || file.renderControllers == null || file.renderControllers.isEmpty()) {
            return null;
        }

        // Pick the first controller — multi-controller files aren't supported.
        BedrockRenderControllerFile.Controller controller = file.renderControllers.values().iterator().next();
        if (controller == null || controller.partVisibility == null || controller.partVisibility.isEmpty()) {
            return new BakedRenderController(List.of(), stamp);
        }

        List<BakedRenderController.Rule> rules = new ArrayList<>();
        for (JsonObject entry : controller.partVisibility) {
            for (Map.Entry<String, JsonElement> kv : entry.entrySet()) {
                String boneGlob = kv.getKey();
                if (!isValidGlob(boneGlob)) {
                    Constants.LOG.warn("[animus-anim] unsupported bone glob '{}' in part_visibility — skipping",
                            boneGlob);
                    continue;
                }
                MolangNode expression = compileVisibilityValue(boneGlob, kv.getValue());
                if (expression != null) {
                    rules.add(new BakedRenderController.Rule(boneGlob, expression));
                }
            }
        }
        return new BakedRenderController(rules, stamp);
    }

    private static MolangNode compileVisibilityValue(String boneGlob, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            Constants.LOG.warn("[animus-anim] null visibility value for bone '{}' — skipping", boneGlob);
            return null;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return new MolangNode.Const(value.getAsBoolean() ? 1.0 : 0.0);
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return new MolangNode.Const(value.getAsDouble());
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return MolangCompiler.compile(value.getAsString());
        }
        Constants.LOG.warn("[animus-anim] unexpected visibility value type for bone '{}': {}",
                boneGlob, value);
        return null;
    }

    private static boolean isValidGlob(String glob) {
        if (glob == null || glob.isEmpty()) return false;
        int stars = 0;
        int starIdx = -1;
        for (int i = 0; i < glob.length(); i++) {
            if (glob.charAt(i) == '*') {
                stars++;
                starIdx = i;
            }
        }
        if (stars == 0) return true;
        if (stars > 1) return false;
        // Single star must be at start or end (or be the whole pattern).
        return starIdx == 0 || starIdx == glob.length() - 1;
    }
}
