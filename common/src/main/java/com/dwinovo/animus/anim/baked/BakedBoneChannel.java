package com.dwinovo.animus.anim.baked;

import com.dwinovo.animus.anim.molang.MolangNode;

/**
 * One animation channel (rotation, position, or scale) for one bone.
 *
 * <p>Storage is data-oriented: keyframes are split into parallel arrays so the
 * sampler can binary-search times once and read 6 floats per keyframe with
 * cache-friendly access. This layout is also {@code repr(C)} friendly for a
 * future Rust port — no boxing, no object pointers in the hot path.
 *
 * <h2>Keyframe layout</h2>
 * Each keyframe stores both a {@code pre} and {@code post} value to handle
 * Bedrock's discontinuity case (a single time stamp with different incoming
 * and outgoing values). When the source JSON only specifies one of the two,
 * the missing side equals the present one. Indices are:
 * <pre>
 *   values[6*i + 0..2] = pre.xyz
 *   values[6*i + 3..5] = post.xyz
 * </pre>
 *
 * <h2>Constant channels</h2>
 * If the source uses the shorthand form {@code "rotation": {"vector": [..]}}
 * with no time keys, the channel is marked {@link #constant}: {@code times}
 * is empty, {@code values} is exactly three floats, and {@code lerpModes}
 * is empty.
 *
 * <h2>Rotations</h2>
 * Rotation values are pre-converted to radians at bake time. Position and
 * scale are stored as-is (pixel units / dimensionless).
 */
public final class BakedBoneChannel {

    public static final byte TYPE_ROTATION = 0;
    public static final byte TYPE_POSITION = 1;
    public static final byte TYPE_SCALE    = 2;

    public static final byte LERP_LINEAR      = 0;
    public static final byte LERP_CATMULL_ROM = 1;
    public static final byte LERP_STEP        = 2;

    public final int boneIdx;
    public final byte channelType;
    public final boolean constant;

    /** Sorted ascending. Length = numKeys (0 for constant channels). */
    public final float[] times;
    /** Length = 6 * numKeys (pre.xyz, post.xyz). For constant channels, length = 3. */
    public final float[] values;
    /** Per-keyframe interpolation mode for the segment starting at this key. Length = numKeys. */
    public final byte[] lerpModes;
    /**
     * Optional per-slot Molang AST. {@code null} for pure-numeric channels (the
     * common case). When non-null, has the same length as {@link #values}; a
     * non-null entry overrides the corresponding numeric slot at sample time.
     * Each entry has the bake-time mirror / unit conversion already wrapped in,
     * so the sampler treats Molang and numeric slots identically.
     */
    public final MolangNode[] molangSlots;

    public BakedBoneChannel(int boneIdx, byte channelType, boolean constant,
                            float[] times, float[] values, byte[] lerpModes,
                            MolangNode[] molangSlots) {
        this.boneIdx = boneIdx;
        this.channelType = channelType;
        this.constant = constant;
        this.times = times;
        this.values = values;
        this.lerpModes = lerpModes;
        this.molangSlots = molangSlots;
    }
}
