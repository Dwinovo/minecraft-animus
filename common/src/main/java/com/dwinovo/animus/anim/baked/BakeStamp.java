package com.dwinovo.animus.anim.baked;

/**
 * Monotonically increasing counter that tags each baked-resource generation.
 *
 * <p>{@link BakedModel} and {@link BakedAnimation} carry the stamp that was
 * current when they were baked. After a {@code F3+T} resource reload, the
 * stamp is bumped and a fresh batch of {@code BakedModel} / {@code BakedAnimation}
 * objects is produced; any cached reference left over from the previous
 * generation will compare unequal and can be detected as stale.
 *
 * <p>This matters because animation channels store {@code boneIdx} resolved
 * against a specific model's bone array — if the model layout changes during a
 * reload (user added or removed bones) the indices baked into a stale
 * animation may be out of bounds for the new model. Detecting the mismatch at
 * extract time and dropping the stale slot prevents the
 * {@code ArrayIndexOutOfBoundsException} that would otherwise crash the next
 * sample.
 *
 * <p>Single-thread access pattern: the loader runs on the resource-reload
 * thread, the renderer reads on the render thread. The
 * {@code volatile} keeps the read-after-write ordering visible across threads
 * without forcing each tick onto a lock.
 */
public final class BakeStamp {

    /** Starts at {@code 1} so {@code 0} can serve as an "unset" sentinel for tests. */
    private static volatile long current = 1L;

    private BakeStamp() {}

    /** Returns the current stamp without advancing. */
    public static long current() {
        return current;
    }

    /** Bumps the stamp and returns the new value; called once per reload. */
    public static synchronized long next() {
        return ++current;
    }
}
