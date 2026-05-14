package com.dwinovo.animus.anim.molang;

import java.util.Arrays;

/**
 * Variable scope for Molang evaluation. Three pre-allocated double arrays —
 * one per namespace ({@code entity.*}, {@code query.*}, {@code variable.*}) —
 * indexed by slot numbers issued by {@link MolangQueries}. No string lookup
 * happens during sampling: the compiler resolves each identifier to a slot at
 * parse time and the AST node carries the integer index directly.
 *
 * <h2>Lifecycle per frame</h2>
 * <ol>
 *   <li>Renderer calls {@link #reset()} to zero all three arrays.</li>
 *   <li>{@code BoneInputProvider}s fill {@code entity.*} and {@code query.*}
 *       slots from {@code AnimusRenderState} / entity-derived state.</li>
 *   <li>{@link com.dwinovo.animus.anim.runtime.PoseSampler} fills
 *       {@code query.anim_time} per animation channel.</li>
 *   <li>{@code variable.*} slots stay at 0 unless an author-supplied
 *       expression assigns into them — assignments aren't supported in this
 *       phase (parser will warn), so currently zeros throughout.</li>
 * </ol>
 *
 * <h2>Slot capacity</h2>
 * Each namespace caps at {@link #SLOTS_PER_NAMESPACE}. Built-ins use a
 * handful; packs introducing more than ~50 unique {@code variable.*} names
 * would need the cap raised. The full 3×64 double allocation is ~1.5 KB per
 * renderer instance — negligible compared to a single baked model.
 */
public final class MolangContext {

    public static final int SLOTS_PER_NAMESPACE = 64;

    public final double[] entitySlots   = new double[SLOTS_PER_NAMESPACE];
    public final double[] querySlots    = new double[SLOTS_PER_NAMESPACE];
    public final double[] variableSlots = new double[SLOTS_PER_NAMESPACE];

    public void reset() {
        Arrays.fill(entitySlots, 0.0);
        Arrays.fill(querySlots, 0.0);
        Arrays.fill(variableSlots, 0.0);
    }

    /** Look up the value for a resolved namespace + slot. Helper for testing. */
    public double get(MolangQueries.Namespace ns, int slot) {
        return switch (ns) {
            case ENTITY -> entitySlots[slot];
            case QUERY -> querySlots[slot];
            case VARIABLE -> variableSlots[slot];
        };
    }
}
