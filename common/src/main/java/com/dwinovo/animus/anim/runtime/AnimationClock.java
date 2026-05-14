package com.dwinovo.animus.anim.runtime;

/**
 * Converts Minecraft's game-time ticks into the nanosecond timeline used by
 * the animation sampler.
 *
 * <p>The sampler still works in nanos for sub-frame precision and simple fade
 * math, but the source must be game time, not wall time. Vanilla freezes
 * {@code tickCount + partialTick} while a true pause screen is open, so using
 * this clock keeps animations frozen under ESC in singleplayer while still
 * advancing normally on multiplayer pause menus.
 */
public final class AnimationClock {
    public static final long NANOS_PER_TICK = 50_000_000L;

    private AnimationClock() {
    }

    public static long fromTicks(int tickCount, float partialTick) {
        float safePartialTick = partialTick < 0f ? 0f : partialTick;
        return tickCount * NANOS_PER_TICK + Math.round(safePartialTick * NANOS_PER_TICK);
    }
}
