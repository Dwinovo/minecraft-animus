package com.dwinovo.animus.anim.compile;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.anim.baked.BakedAnimation;
import com.dwinovo.animus.anim.baked.BakedBoneChannel;
import com.dwinovo.animus.anim.baked.BakedModel;
import com.dwinovo.animus.anim.baked.LoopMode;
import com.dwinovo.animus.anim.molang.MolangNode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles Bedrock {@code .animation.json} files into immutable
 * {@link BakedAnimation}s, expressed against a specific {@link BakedModel}'s
 * bone layout.
 *
 * <p>The animation file schema is heterogeneous (a channel can be a constant
 * vector, a keyframe map, with shorthand or pre/post forms), so we walk the
 * raw {@code JsonObject} rather than introducing a Gson POJO layer that would
 * otherwise be mostly {@code JsonElement} fields anyway.
 *
 * <h2>Numbers vs Molang</h2>
 * Each scalar slot can be either a numeric literal or a Molang expression
 * string. Numeric values are pre-converted (deg→rad, X-mirror) at bake time
 * and stored in the channel's {@code values} array. Molang strings are
 * compiled to a {@link MolangNode} AST, then wrapped in the same axis
 * transform (negate / scale to radians) so the sampler can read both kinds
 * uniformly without knowing about mirror semantics.
 */
public final class AnimationBaker {

    private static final double DEG_TO_RAD = Math.PI / 180.0;
    private static final float DEG_TO_RAD_F = (float) DEG_TO_RAD;

    private static final float[] EMPTY_FLOATS = new float[0];
    private static final byte[]  EMPTY_BYTES  = new byte[0];

    private AnimationBaker() {}

    public static Map<String, BakedAnimation> bake(JsonObject root, BakedModel model) {
        Map<String, BakedAnimation> out = new HashMap<>();
        JsonElement animsEl = root.get("animations");
        if (animsEl == null || !animsEl.isJsonObject()) return out;

        int totalChannels = 0;
        for (Map.Entry<String, JsonElement> entry : animsEl.getAsJsonObject().entrySet()) {
            String name = entry.getKey();
            if (!entry.getValue().isJsonObject()) continue;
            try {
                BakedAnimation baked = bakeOne(name, entry.getValue().getAsJsonObject(), model);
                out.put(name, baked);
                totalChannels += baked.channels.length;
            } catch (Exception ex) {
                Constants.LOG.error("[animus-anim] failed to bake animation {}: {}", name, ex.toString());
            }
        }
        Constants.LOG.debug("[animus-anim] baked {} animations, {} live channels (identity channels pruned)",
                out.size(), totalChannels);
        return out;
    }

    private static BakedAnimation bakeOne(String name, JsonObject obj, BakedModel model) {
        // Bedrock 'loop' is tri-state: false | true | "hold_on_last_frame"
        // — the typed enum keeps the third value alive through the pipeline.
        LoopMode loopMode = obj.has("loop") ? LoopMode.fromBedrockJson(obj.get("loop")) : LoopMode.PLAY_ONCE;
        float duration = obj.has("animation_length") ? obj.get("animation_length").getAsFloat() : 0f;

        List<BakedBoneChannel> channels = new ArrayList<>();
        JsonElement bonesEl = obj.get("bones");
        if (bonesEl != null && bonesEl.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : bonesEl.getAsJsonObject().entrySet()) {
                Integer boneIdx = model.boneIndex.get(e.getKey());
                if (boneIdx == null) continue;
                if (!e.getValue().isJsonObject()) continue;
                addBoneChannels(boneIdx, e.getValue().getAsJsonObject(), channels);
            }
        }

        if (duration <= 0f) {
            duration = computeDuration(channels);
        }
        return new BakedAnimation(name, duration, loopMode, channels.toArray(new BakedBoneChannel[0]), model.bakeStamp);
    }

    private static void addBoneChannels(int boneIdx, JsonObject boneAnim, List<BakedBoneChannel> out) {
        JsonElement rot = boneAnim.get("rotation");
        if (rot != null) bakeChannel(boneIdx, BakedBoneChannel.TYPE_ROTATION, rot, out);
        JsonElement pos = boneAnim.get("position");
        if (pos != null) bakeChannel(boneIdx, BakedBoneChannel.TYPE_POSITION, pos, out);
        JsonElement scale = boneAnim.get("scale");
        if (scale != null) bakeChannel(boneIdx, BakedBoneChannel.TYPE_SCALE, scale, out);
    }

    private static void bakeChannel(int boneIdx, byte type, JsonElement data, List<BakedBoneChannel> out) {
        if (data.isJsonArray()) {
            // Bare-array shorthand ("rotation": [x, y, z]).
            Vec3WithMolang v = readVec3(data);
            applyMirrorAndUnits(v, type);
            if (isConstantIdentity(type, v)) return;
            out.add(new BakedBoneChannel(boneIdx, type, true, EMPTY_FLOATS, v.num, EMPTY_BYTES, v.molang));
            return;
        }
        if (!data.isJsonObject()) return;
        JsonObject obj = data.getAsJsonObject();

        // Detect form: shorthand "vector" with no time keys = constant channel.
        if (obj.has("vector") && !hasTimeKeys(obj)) {
            Vec3WithMolang v = readVec3(obj.get("vector"));
            applyMirrorAndUnits(v, type);
            if (isConstantIdentity(type, v)) return;
            out.add(new BakedBoneChannel(boneIdx, type, true, EMPTY_FLOATS, v.num, EMPTY_BYTES, v.molang));
            return;
        }

        // Keyframed form: gather all numeric keys, sort by time.
        List<TimedKey> keys = new ArrayList<>(obj.size());
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            float t;
            try {
                t = Float.parseFloat(e.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }
            if (!e.getValue().isJsonObject() && !e.getValue().isJsonArray()) continue;
            keys.add(new TimedKey(t, e.getValue()));
        }
        if (keys.isEmpty()) return;
        keys.sort(Comparator.comparingDouble(k -> k.time));

        int n = keys.size();
        float[] times = new float[n];
        float[] values = new float[n * 6];
        byte[] lerpModes = new byte[n];
        MolangNode[] molangSlots = null;

        for (int i = 0; i < n; i++) {
            TimedKey key = keys.get(i);
            times[i] = key.time;

            Vec3WithMolang pre;
            Vec3WithMolang post;
            byte lerp = BakedBoneChannel.LERP_LINEAR;

            if (key.value.isJsonArray()) {
                Vec3WithMolang v = readVec3(key.value);
                pre = v;
                post = v.shallowCopy();
            } else {
                JsonObject kf = key.value.getAsJsonObject();
                if (kf.has("vector")) {
                    Vec3WithMolang v = readVec3(kf.get("vector"));
                    pre = v;
                    post = v.shallowCopy();
                } else {
                    JsonElement preEl = kf.get("pre");
                    JsonElement postEl = kf.get("post");
                    if (preEl == null && postEl == null) {
                        pre = Vec3WithMolang.zero();
                        post = Vec3WithMolang.zero();
                    } else if (preEl == null) {
                        post = readVec3FromKfPart(postEl);
                        pre = post.shallowCopy();
                    } else if (postEl == null) {
                        pre = readVec3FromKfPart(preEl);
                        post = pre.shallowCopy();
                    } else {
                        pre = readVec3FromKfPart(preEl);
                        post = readVec3FromKfPart(postEl);
                    }
                }
                if (kf.has("lerp_mode")) {
                    JsonElement lm = kf.get("lerp_mode");
                    if (lm.isJsonPrimitive() && lm.getAsJsonPrimitive().isString()) {
                        lerp = parseLerpMode(lm.getAsString());
                    }
                }
            }

            applyMirrorAndUnits(pre, type);
            applyMirrorAndUnits(post, type);

            int base = i * 6;
            values[base]     = pre.num[0];
            values[base + 1] = pre.num[1];
            values[base + 2] = pre.num[2];
            values[base + 3] = post.num[0];
            values[base + 4] = post.num[1];
            values[base + 5] = post.num[2];

            if (pre.molang != null || post.molang != null) {
                if (molangSlots == null) molangSlots = new MolangNode[n * 6];
                if (pre.molang != null) {
                    molangSlots[base]     = pre.molang[0];
                    molangSlots[base + 1] = pre.molang[1];
                    molangSlots[base + 2] = pre.molang[2];
                }
                if (post.molang != null) {
                    molangSlots[base + 3] = post.molang[0];
                    molangSlots[base + 4] = post.molang[1];
                    molangSlots[base + 5] = post.molang[2];
                }
            }
            lerpModes[i] = lerp;
        }

        if (molangSlots == null && isKeyframedIdentity(type, values)) return;
        out.add(new BakedBoneChannel(boneIdx, type, false, times, values, lerpModes, molangSlots));
    }

    /**
     * True when a constant channel's value is the no-op identity for its
     * type — rotation/position must be all zero, scale must be all one.
     * Identity channels never affect the pose buffer (which is reset to
     * identity each frame), so dropping them at bake time saves a per-frame
     * sample call and a buffer write per skipped channel.
     */
    private static boolean isConstantIdentity(byte type, Vec3WithMolang v) {
        if (v.molang != null) return false;
        if (type == BakedBoneChannel.TYPE_SCALE) {
            return v.num[0] == 1f && v.num[1] == 1f && v.num[2] == 1f;
        }
        return v.num[0] == 0f && v.num[1] == 0f && v.num[2] == 0f;
    }

    /**
     * True when every keyframe slot in {@code values} (laid out as 6 floats
     * per key — pre.xyz then post.xyz) is the identity value for the channel
     * type. Some authoring tools emit fully-zero rotation tracks alongside
     * real data; pruning them avoids the binary search + interpolation work
     * for a channel that contributes nothing.
     */
    private static boolean isKeyframedIdentity(byte type, float[] values) {
        float identity = type == BakedBoneChannel.TYPE_SCALE ? 1f : 0f;
        for (int i = 0; i < values.length; i++) {
            if (values[i] != identity) return false;
        }
        return true;
    }

    private static boolean hasTimeKeys(JsonObject obj) {
        for (String key : obj.keySet()) {
            try {
                Float.parseFloat(key);
                return true;
            } catch (NumberFormatException ignored) {
                // not a numeric key
            }
        }
        return false;
    }

    private static Vec3WithMolang readVec3(JsonElement el) {
        float[] num = new float[3];
        MolangNode[] molang = null;
        if (el == null || !el.isJsonArray()) return new Vec3WithMolang(num, null);
        JsonArray arr = el.getAsJsonArray();
        int n = Math.min(3, arr.size());
        for (int i = 0; i < n; i++) {
            JsonElement c = arr.get(i);
            if (!c.isJsonPrimitive()) continue;
            JsonPrimitive p = c.getAsJsonPrimitive();
            if (p.isNumber()) {
                num[i] = p.getAsFloat();
            } else if (p.isString()) {
                String s = p.getAsString();
                try {
                    num[i] = Float.parseFloat(s);
                } catch (NumberFormatException ignored) {
                    if (molang == null) molang = new MolangNode[3];
                    molang[i] = MolangCompiler.compile(s);
                }
            }
        }
        return new Vec3WithMolang(num, molang);
    }

    private static Vec3WithMolang readVec3FromKfPart(JsonElement kfPart) {
        if (kfPart == null) return Vec3WithMolang.zero();
        if (kfPart.isJsonArray()) return readVec3(kfPart);
        if (kfPart.isJsonObject()) {
            JsonObject o = kfPart.getAsJsonObject();
            if (o.has("vector")) return readVec3(o.get("vector"));
        }
        return Vec3WithMolang.zero();
    }

    /**
     * In-place mirror + unit conversion to match the bake-time X flip applied
     * to {@link BakedModel} bones. Both numeric and Molang slots get the same
     * treatment so the sampler can ignore the difference.
     *
     * <ul>
     *   <li>{@code rotation}: convert to radians, negate X and Y, keep Z.</li>
     *   <li>{@code position}: negate X.</li>
     *   <li>{@code scale}: unchanged (mirror-symmetric).</li>
     * </ul>
     */
    private static void applyMirrorAndUnits(Vec3WithMolang v, byte type) {
        if (type == BakedBoneChannel.TYPE_ROTATION) {
            v.num[0] = -v.num[0] * DEG_TO_RAD_F;
            v.num[1] = -v.num[1] * DEG_TO_RAD_F;
            v.num[2] =  v.num[2] * DEG_TO_RAD_F;
            if (v.molang != null) {
                v.molang[0] = wrap(v.molang[0], -DEG_TO_RAD);
                v.molang[1] = wrap(v.molang[1], -DEG_TO_RAD);
                v.molang[2] = wrap(v.molang[2],  DEG_TO_RAD);
            }
        } else if (type == BakedBoneChannel.TYPE_POSITION) {
            v.num[0] = -v.num[0];
            if (v.molang != null) {
                v.molang[0] = wrap(v.molang[0], -1.0);
            }
        }
    }

    /** Wraps a Molang node so its result is multiplied by {@code factor}. {@code null} stays {@code null}. */
    private static MolangNode wrap(MolangNode n, double factor) {
        if (n == null) return null;
        if (factor == 1.0) return n;
        return new MolangNode.Mul(new MolangNode.Const(factor), n);
    }

    private static byte parseLerpMode(String s) {
        if (s == null) return BakedBoneChannel.LERP_LINEAR;
        return switch (s) {
            case "catmullrom" -> BakedBoneChannel.LERP_CATMULL_ROM;
            case "step" -> BakedBoneChannel.LERP_STEP;
            default -> BakedBoneChannel.LERP_LINEAR;
        };
    }

    private static float computeDuration(List<BakedBoneChannel> channels) {
        float max = 0f;
        for (BakedBoneChannel ch : channels) {
            if (ch.times.length > 0) {
                max = Math.max(max, ch.times[ch.times.length - 1]);
            }
        }
        return max;
    }

    private record TimedKey(float time, JsonElement value) {}

    /**
     * Mutable carrier used during baking: numeric values + parallel optional
     * Molang nodes. We mutate it in {@code applyMirrorAndUnits} rather than
     * allocating new arrays.
     */
    private static final class Vec3WithMolang {
        final float[] num;
        MolangNode[] molang;

        Vec3WithMolang(float[] num, MolangNode[] molang) {
            this.num = num;
            this.molang = molang;
        }

        static Vec3WithMolang zero() {
            return new Vec3WithMolang(new float[3], null);
        }

        Vec3WithMolang shallowCopy() {
            float[] n = new float[3];
            System.arraycopy(num, 0, n, 0, 3);
            MolangNode[] m = null;
            if (molang != null) {
                m = new MolangNode[3];
                System.arraycopy(molang, 0, m, 0, 3);
            }
            return new Vec3WithMolang(n, m);
        }
    }
}
