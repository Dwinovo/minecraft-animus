package com.dwinovo.animus.anim.runtime;

import com.dwinovo.animus.anim.baked.BakedAnimation;
import com.dwinovo.animus.anim.baked.BakedBoneChannel;
import com.dwinovo.animus.anim.controller.BlendMode;
import com.dwinovo.animus.anim.molang.MolangContext;
import com.dwinovo.animus.anim.molang.MolangNode;

/**
 * Pure-function pose sampler. The core of solving double-extract timing drift:
 * given an animation, a per-channel start time, and the current pause-aware
 * animation-clock time, produces the exact pose for that instant. Calling
 * this twice in the same frame returns identical output — no mutable
 * {@code lastAnimatableAge} counter to advance, so {@code InventoryScreen}'s
 * second {@code extractRenderState} call cannot accidentally double-step
 * animation.
 *
 * <h2>Pose buffer layout</h2>
 * Caller-owned, reused across frames. Per bone:
 * <pre>
 *   [b * 9 + 0..2] rotation delta  (radians, XYZ Euler, applied on top of rest pose)
 *   [b * 9 + 3..5] position offset (pixel units, applied as a translate)
 *   [b * 9 + 6..8] scale           (multiplier; identity = 1)
 * </pre>
 * Use {@link #resetIdentity} before each frame to clear stale data.
 *
 * <h2>Blend modes</h2>
 * The {@link BlendMode} parameter selects how this controller's contribution
 * combines with whatever earlier controllers wrote into the pose buffer:
 * <ul>
 *   <li>{@link BlendMode#OVERRIDE} writes the sampled value directly. Later
 *       controllers fully replace earlier ones on shared (bone × channel-type)
 *       tuples.</li>
 *   <li>{@link BlendMode#ADDITIVE} adds rotation/position values to the
 *       existing buffer entries, and <em>multiplies</em> scale (because
 *       {@code 1.0} is the scale identity). This mirrors GeckoLib's
 *       {@code AnimationProcessor} additive math — naive {@code +=} on scale
 *       compounds {@code 1+1=2} per controller and blows up the model.</li>
 * </ul>
 * Channel-types the sampled animation does not keyframe leave the pose buffer
 * untouched regardless of mode.
 *
 * <h2>Molang slots</h2>
 * Channels carry an optional parallel {@link MolangNode} array. When a slot
 * is non-null, it is evaluated against the supplied {@link MolangContext}
 * and overrides the numeric value at that slot. The compiler has already
 * wrapped each node so its output includes the same X-mirror / unit
 * conversion as the numeric path — no special-case branching here.
 */
public final class PoseSampler {

    public static final int FLOATS_PER_BONE = 9;
    public static final int OFFSET_ROT   = 0;
    public static final int OFFSET_POS   = 3;
    public static final int OFFSET_SCALE = 6;

    private PoseSampler() {}

    public static void resetIdentity(float[] poseBuf, int boneCount) {
        for (int b = 0; b < boneCount; b++) {
            int base = b * FLOATS_PER_BONE;
            poseBuf[base]     = 0f;  poseBuf[base + 1] = 0f;  poseBuf[base + 2] = 0f;
            poseBuf[base + 3] = 0f;  poseBuf[base + 4] = 0f;  poseBuf[base + 5] = 0f;
            poseBuf[base + 6] = 1f;  poseBuf[base + 7] = 1f;  poseBuf[base + 8] = 1f;
        }
    }

    /**
     * Default-mode (OVERRIDE) sample, kept for the simple test paths and the
     * BASE-loop crossfade buffer-blend.
     */
    public static void sample(AnimationChannel channel, long nowNs,
                              MolangContext ctx, float[] poseBuf) {
        sample(channel, BlendMode.OVERRIDE, nowNs, ctx, poseBuf);
    }

    /**
     * Samples one channel into {@code poseBuf} using the given blend mode.
     * {@code null} channels are skipped. Updates
     * {@code ctx.querySlots[QUERY_ANIM_TIME]} so any Molang nodes referencing
     * {@code query.anim_time} see this channel's local time.
     */
    public static void sample(AnimationChannel channel, BlendMode mode, long nowNs,
                              MolangContext ctx, float[] poseBuf) {
        if (channel == null) return;
        BakedAnimation anim = channel.animation();
        if (anim == null) return;
        float t = computeAnimTime(channel, nowNs);
        ctx.querySlots[com.dwinovo.animus.anim.molang.MolangQueries.QUERY_ANIM_TIME] = t;
        for (BakedBoneChannel ch : anim.channels) {
            applyChannel(ch, mode, t, ctx, poseBuf);
        }
    }

    public static float computeAnimTime(AnimationChannel channel, long nowNs) {
        BakedAnimation anim = channel.animation();
        float elapsed = (float) ((nowNs - channel.startTimeNs()) / 1.0e9);
        if (elapsed < 0f) elapsed = 0f;
        if (anim.durationSec <= 0f) return 0f;
        if (channel.looping()) {
            elapsed = elapsed % anim.durationSec;
            if (elapsed < 0f) elapsed += anim.durationSec;
        } else if (elapsed > anim.durationSec) {
            elapsed = anim.durationSec;
        }
        return elapsed;
    }

    private static void applyChannel(BakedBoneChannel ch, BlendMode mode, float t, MolangContext ctx, float[] poseBuf) {
        int base = ch.boneIdx * FLOATS_PER_BONE + offsetFor(ch.channelType);
        float vx, vy, vz;
        if (ch.constant) {
            vx = readSlot(ch, 0, ctx);
            vy = readSlot(ch, 1, ctx);
            vz = readSlot(ch, 2, ctx);
        } else {
            float[] times = ch.times;
            int n = times.length;
            if (n == 0) return;

            if (t <= times[0]) {
                vx = readSlot(ch, 0, ctx);
                vy = readSlot(ch, 1, ctx);
                vz = readSlot(ch, 2, ctx);
            } else if (t >= times[n - 1]) {
                int last = (n - 1) * 6;
                vx = readSlot(ch, last + 3, ctx);
                vy = readSlot(ch, last + 4, ctx);
                vz = readSlot(ch, last + 5, ctx);
            } else {
                int i = binarySearchLE(times, t);
                if (i >= n - 1) i = n - 2;
                float t0 = times[i];
                float t1 = times[i + 1];
                float dt = t1 - t0;
                float u = dt > 1.0e-6f ? (t - t0) / dt : 0f;
                if (u < 0f) u = 0f; else if (u > 1f) u = 1f;

                byte lerp = ch.lerpModes[i];
                int p1Base = i * 6 + 3;        // post[i]
                int p2Base = (i + 1) * 6;      // pre[i+1]

                if (lerp == BakedBoneChannel.LERP_STEP) {
                    vx = readSlot(ch, p1Base,     ctx);
                    vy = readSlot(ch, p1Base + 1, ctx);
                    vz = readSlot(ch, p1Base + 2, ctx);
                } else if (lerp == BakedBoneChannel.LERP_CATMULL_ROM) {
                    int p0Base = i > 0 ? (i - 1) * 6 + 3 : p1Base;
                    int p3Base = (i + 2 < n) ? (i + 2) * 6 : p2Base;
                    vx = catmullRom(readSlot(ch, p0Base,     ctx), readSlot(ch, p1Base,     ctx),
                                     readSlot(ch, p2Base,     ctx), readSlot(ch, p3Base,     ctx), u);
                    vy = catmullRom(readSlot(ch, p0Base + 1, ctx), readSlot(ch, p1Base + 1, ctx),
                                     readSlot(ch, p2Base + 1, ctx), readSlot(ch, p3Base + 1, ctx), u);
                    vz = catmullRom(readSlot(ch, p0Base + 2, ctx), readSlot(ch, p1Base + 2, ctx),
                                     readSlot(ch, p2Base + 2, ctx), readSlot(ch, p3Base + 2, ctx), u);
                } else {
                    float a0 = readSlot(ch, p1Base,     ctx);
                    float a1 = readSlot(ch, p1Base + 1, ctx);
                    float a2 = readSlot(ch, p1Base + 2, ctx);
                    float b0 = readSlot(ch, p2Base,     ctx);
                    float b1 = readSlot(ch, p2Base + 1, ctx);
                    float b2 = readSlot(ch, p2Base + 2, ctx);
                    vx = a0 + (b0 - a0) * u;
                    vy = a1 + (b1 - a1) * u;
                    vz = a2 + (b2 - a2) * u;
                }
            }
        }
        writeBlended(poseBuf, base, ch.channelType, mode, vx, vy, vz);
    }

    /**
     * Combines the sampled triplet with the existing pose-buffer values per
     * the requested {@link BlendMode}.
     *
     * <ul>
     *   <li>OVERRIDE: write directly (the historical behaviour).</li>
     *   <li>ADDITIVE on rotation / position: {@code +=}.</li>
     *   <li>ADDITIVE on scale: {@code *=} (since the scale identity is 1, not 0).</li>
     * </ul>
     */
    private static void writeBlended(float[] poseBuf, int base, byte channelType, BlendMode mode,
                                     float vx, float vy, float vz) {
        if (mode == BlendMode.OVERRIDE) {
            poseBuf[base]     = vx;
            poseBuf[base + 1] = vy;
            poseBuf[base + 2] = vz;
            return;
        }
        if (channelType == BakedBoneChannel.TYPE_SCALE) {
            poseBuf[base]     *= vx;
            poseBuf[base + 1] *= vy;
            poseBuf[base + 2] *= vz;
        } else {
            poseBuf[base]     += vx;
            poseBuf[base + 1] += vy;
            poseBuf[base + 2] += vz;
        }
    }

    private static int offsetFor(byte type) {
        return switch (type) {
            case BakedBoneChannel.TYPE_ROTATION -> OFFSET_ROT;
            case BakedBoneChannel.TYPE_POSITION -> OFFSET_POS;
            case BakedBoneChannel.TYPE_SCALE   -> OFFSET_SCALE;
            default -> 0;
        };
    }

    /** Returns slot {@code i}'s value, evaluating its Molang node if one is bound. */
    private static float readSlot(BakedBoneChannel ch, int slotIdx, MolangContext ctx) {
        MolangNode[] slots = ch.molangSlots;
        if (slots != null && slotIdx < slots.length && slots[slotIdx] != null) {
            return (float) slots[slotIdx].eval(ctx);
        }
        return ch.values[slotIdx];
    }

    private static float catmullRom(float p0, float p1, float p2, float p3, float u) {
        float u2 = u * u;
        float u3 = u2 * u;
        return 0.5f * (
                (2f * p1)
                        + (-p0 + p2) * u
                        + (2f * p0 - 5f * p1 + 4f * p2 - p3) * u2
                        + (-p0 + 3f * p1 - 3f * p2 + p3) * u3
        );
    }

    private static int binarySearchLE(float[] arr, float t) {
        int lo = 0;
        int hi = arr.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (arr[mid] <= t) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }
}
