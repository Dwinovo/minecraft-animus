package com.dwinovo.animus.platform.services;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Function;

/**
 * Loader-abstracted access to a server-side fake player, used to perform
 * real vanilla right-click item interactions (flint&steel, ender eye, bucket,
 * bonemeal, …) on behalf of an Animus — which is a {@code Mob}, not a
 * {@code Player}, and so can't call the player-centric {@code gameMode.useItem*}
 * paths directly.
 *
 * <h2>Why this is loader-specific</h2>
 * Only the <em>construction</em> differs: Fabric has
 * {@code net.fabricmc.fabric.api.entity.FakePlayer.get(level)}; NeoForge has
 * {@code FakePlayerFactory.getMinecraft(level)}. Both return a
 * {@link ServerPlayer} subtype, so every actual use-logic + inventory
 * reconciliation lives in common (see {@code FakePlayerUse}).
 *
 * <h2>Leak safety (the important part)</h2>
 * The fake player is a <strong>single cached instance per level</strong> (fixed
 * profile) — reused across all calls, never created-per-task — so there is
 * nothing to accumulate (this sidesteps the known NeoForge fake-player memory
 * leak from per-call UUIDs). Callers MUST go through {@link #withFakePlayer},
 * which resets the instance's state (clears its inventory, stops any in-progress
 * use) both before and — in a {@code finally} — after the action, so no item or
 * state ever leaks between tasks. Animus tasks run serially (single FIFO queue),
 * so there is never concurrent use of the shared instance.
 */
public interface IFakePlayerBridge {

    /** The shared, cached fake player for {@code level} (loader-specific construction). */
    ServerPlayer getFakePlayer(ServerLevel level);

    /**
     * Run {@code action} with a freshly-reset fake player, then always reset it
     * again so the shared instance is left clean. Returns the action's result.
     */
    default <T> T withFakePlayer(ServerLevel level, Function<ServerPlayer, T> action) {
        ServerPlayer fp = getFakePlayer(level);
        reset(fp);
        try {
            return action.apply(fp);
        } finally {
            reset(fp);
        }
    }

    /**
     * Borrow the fake player ACROSS ticks — held uses (a bow charging) need
     * the use-item state to survive between server ticks, which the
     * scoped {@link #withFakePlayer} reset discipline forbids. The caller
     * owns cleanup: {@link #releaseLease} MUST run on every exit path (the
     * task goal's stop/buildResult bottoms it out). Serial task execution is
     * what makes a single shared lease sound — there is never a second
     * borrower.
     */
    default ServerPlayer acquireLease(ServerLevel level) {
        ServerPlayer fp = getFakePlayer(level);
        reset(fp);
        return fp;
    }

    /** Return a leased fake player, wiping all state. Idempotent. */
    default void releaseLease(ServerPlayer fp) {
        reset(fp);
    }

    /** Drop all held/used state so nothing leaks to the next task. */
    private static void reset(ServerPlayer fp) {
        fp.stopUsingItem();
        fp.getInventory().clearContent();
    }
}
