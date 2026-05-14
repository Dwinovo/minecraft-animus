package com.dwinovo.animus.anim.baked;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * Three-state loop semantic that mirrors Bedrock's
 * {@code "loop": false | true | "hold_on_last_frame"} animation file field.
 *
 * <p>The Bedrock spec (and Blockbench's animation editor UI) lets the
 * animator declare what should happen when the animation reaches its end.
 * Reading the declaration as a typed enum — rather than a boolean that
 * silently downgrades {@code "hold_on_last_frame"} to {@code false} — keeps
 * the animator's intent intact through the bake pipeline:
 *
 * <ul>
 *   <li>{@link #PLAY_ONCE} — runs once and lets the slot self-clear; the
 *       bone returns to its bind pose / BASE-slot pose afterwards. Maps to
 *       JSON {@code "loop": false} (Bedrock default).</li>
 *   <li>{@link #LOOP} — wraps {@code anim_time % duration} forever. Maps to
 *       JSON {@code "loop": true}.</li>
 *   <li>{@link #HOLD_ON_LAST_FRAME} — runs once and freezes at the last
 *       keyframe. The slot is <em>not</em> cleared so the pose persists.
 *       Maps to JSON {@code "loop": "hold_on_last_frame"}.</li>
 * </ul>
 *
 * <p>This is metadata about the animation file itself, distinct from
 * {@link com.dwinovo.animus.anim.runtime.AnimationChannel#looping()} which
 * is the runtime "should the sampler wrap?" override and lives on the
 * playback instance, not the animation data.
 */
public enum LoopMode {
    PLAY_ONCE,
    LOOP,
    HOLD_ON_LAST_FRAME;

    /**
     * Decodes a Bedrock {@code loop} field, tolerating common authoring
     * variations (boolean, the documented {@code "hold_on_last_frame"}
     * string, plus the stringified booleans some legacy exporters emit).
     * Falls back to {@link #PLAY_ONCE} (Bedrock's documented default) on
     * any unrecognised input.
     */
    public static LoopMode fromBedrockJson(JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return PLAY_ONCE;
        }
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean() ? LOOP : PLAY_ONCE;
        }
        if (p.isString()) {
            String s = p.getAsString();
            if ("hold_on_last_frame".equalsIgnoreCase(s)) return HOLD_ON_LAST_FRAME;
            if ("true".equalsIgnoreCase(s)) return LOOP;
            if ("false".equalsIgnoreCase(s)) return PLAY_ONCE;
        }
        return PLAY_ONCE;
    }
}
