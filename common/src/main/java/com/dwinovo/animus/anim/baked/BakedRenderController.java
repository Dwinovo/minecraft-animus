package com.dwinovo.animus.anim.baked;

import com.dwinovo.animus.anim.molang.MolangContext;
import com.dwinovo.animus.anim.molang.MolangNode;

import java.util.List;

/**
 * Immutable baked form of a Bedrock {@code render_controllers.json} controller.
 * Only the {@code part_visibility} array is materialised — every other Bedrock
 * field is purely descriptive in Animus, so the bake step drops them.
 *
 * <h2>Visibility semantics</h2>
 * {@link #rules} are evaluated in array order against the model's bones.
 * Each rule's {@link Rule#expression} is a compiled Molang AST that returns
 * a double — non-zero means "visible", zero means "hidden". The result
 * <em>overwrites</em> the previous decision for every bone the
 * {@link Rule#boneGlob} matches (Bedrock last-write-wins).
 *
 * <h2>Bone glob syntax</h2>
 * <ul>
 *   <li>{@code "*"} — every bone</li>
 *   <li>{@code "prefix*"} — names starting with {@code prefix}</li>
 *   <li>{@code "*suffix"} — names ending with {@code suffix}</li>
 *   <li>{@code "exact_name"} — exact match (no wildcards)</li>
 * </ul>
 * More complex regex / multi-wildcard patterns are intentionally rejected
 * by {@link com.dwinovo.animus.anim.compile.RenderControllerBaker}.
 */
public final class BakedRenderController {

    public final List<Rule> rules;
    public final long bakeStamp;

    public BakedRenderController(List<Rule> rules, long bakeStamp) {
        this.rules = List.copyOf(rules);
        this.bakeStamp = bakeStamp;
    }

    public record Rule(String boneGlob, MolangNode expression) {

        /** Resolved hidden flag for one bone. */
        public boolean isHiddenFor(MolangContext ctx) {
            return expression.eval(ctx) == 0.0;
        }
    }

    /**
     * Apply this controller's rules to the per-bone hidden array. Each rule
     * is evaluated once; matched bones get their hidden flag set to the
     * inverse of the expression's truthiness.
     *
     * <p>Caller-owned {@code hidden} array is mutated in place — the renderer
     * is expected to pre-populate it with any Java-lambda-derived state and
     * let this method overwrite where Molang rules apply.
     */
    public void applyTo(BakedModel model, MolangContext ctx, boolean[] hidden) {
        for (Rule rule : rules) {
            boolean hiddenValue = rule.isHiddenFor(ctx);
            for (int i = 0; i < model.bones.length; i++) {
                if (matchesGlob(rule.boneGlob, model.bones[i].name)) {
                    hidden[i] = hiddenValue;
                }
            }
        }
    }

    /**
     * Glob match for bone-visibility rules. See class-level docs for supported
     * patterns.
     */
    public static boolean matchesGlob(String glob, String name) {
        if (glob.equals("*")) return true;
        int star = glob.indexOf('*');
        if (star < 0) return glob.equals(name);
        if (star == glob.length() - 1) {
            return name.startsWith(glob.substring(0, star));
        }
        if (star == 0) {
            return name.endsWith(glob.substring(1));
        }
        // Multi-wildcard / middle-wildcard patterns intentionally unsupported;
        // RenderControllerBaker filters these out at compile time.
        return false;
    }
}
