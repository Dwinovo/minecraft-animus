package com.dwinovo.animus.anim.controller;

/**
 * How a controller's sampled values combine with whatever earlier controllers
 * have already written into the shared pose buffer.
 *
 * <p>Applied per (bone × channel-type) tuple — channels the controller's
 * animation does not keyframe leave the pose buffer untouched regardless of
 * the mode. This means an animator authoring a "breath" controller only
 * keyframes the chest's rotation; position and scale stay whatever the BASE
 * controller wrote.
 *
 * <p>The math intentionally diverges per channel type, mirroring GeckoLib's
 * {@code AnimationProcessor} semantics:
 * <ul>
 *   <li>{@link #OVERRIDE} writes the sampled value directly to every channel
 *       it animates — later controllers fully replace earlier ones on shared
 *       channels. The default mode.</li>
 *   <li>{@link #ADDITIVE} adds rotation and position deltas to existing
 *       values, and <em>multiplies</em> scale (since {@code 1.0} is the scale
 *       identity). A naive {@code +=} for scale would compound to {@code 2.0}
 *       on each frame's accumulation, blowing up the model.</li>
 * </ul>
 */
public enum BlendMode {
    OVERRIDE,
    ADDITIVE
}
