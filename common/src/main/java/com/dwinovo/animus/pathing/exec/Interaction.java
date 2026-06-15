package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.entity.AnimusPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The most-native interaction primitive for a fake-player body — modelled on
 * Carpet's {@code EntityPlayerActionPack}: aim the eyes at a target, then "press"
 * one mouse button (left = ATTACK, right = USE) with a {@link Timing}. Every
 * higher-level action is a thin layer on top: {@code break_block} = ATTACK a
 * block (hold), {@code place_block} = USE a block (once), {@code hunt} = ATTACK an
 * entity, eat/bow = hold USE in the air.
 *
 * <h2>Native dispatch (same calls Carpet's action pack makes)</h2>
 * <ul>
 *   <li>ATTACK + block  → {@link BlockDigger} (creative insta / survival timed) → {@code handleBlockBreakAction} START/STOP (server destroys)</li>
 *   <li>ATTACK + entity → {@code player.attack} (cooldown-scaled damage / sweep / knockback)</li>
 *   <li>USE + block     → {@code gameMode.useItemOn} (vanilla place / activate), both hands tried</li>
 *   <li>USE + entity    → {@code entity.interact} then {@code player.interactOn} (trade / breed / mount), both hands</li>
 *   <li>USE + air       → {@code gameMode.useItem} (+ a hold for food / bow)</li>
 * </ul>
 *
 * <h2>Timing (Carpet's {@code Action} model, first-class)</h2>
 * {@link Timing#once()} taps once; {@link Timing#repeat} taps N times spaced by an
 * interval (auto-click a button, grind a mob); {@link Timing#hold()} holds the
 * button until the action self-completes (a block breaks, food finishes);
 * {@link Timing#hold(int)} holds up to N ticks then releases (draw + loose a bow).
 *
 * <p>Stateful + ticked (like {@link BlockDigger} / {@code PlayerNav}). The caller
 * walks the body within reach first; this only aims and presses.
 */
public final class Interaction {

    public enum Status { RUNNING, DONE, FAILED }
    public enum Button { ATTACK, USE }

    /** Vanilla block-interaction reach (survival); creative is 5. */
    private static final double REACH = 4.5;
    /** The two hands USE tries, main first (Carpet tries both). */
    private static final InteractionHand[] HANDS = {InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND};

    /**
     * When and how often the button fires — a port of Carpet's {@code Action}
     * (once / continuous / interval). {@code hold} actions press-and-hold until
     * the action finishes on its own (breaking, eating) or {@code maxHold} elapses
     * (bow); discrete actions fire {@code limit} times spaced by {@code interval}.
     */
    public static final class Timing {
        final boolean hold;
        final int limit;     // discrete fires (>=1); ignored for hold
        final int interval;  // ticks between discrete fires (>=1)
        final int maxHold;   // hold: release after this many ticks; 0 = until self-complete

        private Timing(boolean hold, int limit, int interval, int maxHold) {
            this.hold = hold;
            this.limit = limit;
            this.interval = interval;
            this.maxHold = maxHold;
        }

        /** One single press. */
        public static Timing once() {
            return new Timing(false, 1, 1, 0);
        }

        /** {@code times} presses, each spaced {@code interval} ticks apart. */
        public static Timing repeat(int times, int interval) {
            return new Timing(false, Math.max(1, times), Math.max(1, interval), 0);
        }

        /** Hold until the action finishes on its own (block broken / food eaten). */
        public static Timing hold() {
            return new Timing(true, -1, 1, 0);
        }

        /** Hold up to {@code maxTicks}, then release (e.g. draw a bow and loose). */
        public static Timing hold(int maxTicks) {
            return new Timing(true, -1, 1, Math.max(1, maxTicks));
        }
    }

    private final AnimusPlayer player;
    private final Button button;
    private final BlockPos block;     // non-null → block target
    private final Entity entity;      // non-null → entity target
    private final InteractionHand hand;
    private final Timing timing;

    private final BlockDigger digger; // only for ATTACK + block
    private BlockHitResult presetHit; // USE+block: an exact hit the caller already resolved (placement)
    private int fires;
    private int cooldown;             // ticks until the next discrete press
    private boolean started;          // USE+air: the hold has begun
    private int held;                 // USE+air: ticks held so far
    private boolean hardFail;         // a fire hit an unrecoverable error
    private String failReason = "interaction failed";

    private Interaction(AnimusPlayer player, Button button, BlockPos block, Entity entity,
                        InteractionHand hand, Timing timing) {
        this.player = player;
        this.button = button;
        this.block = block == null ? null : block.immutable();
        this.entity = entity;
        this.hand = hand;
        this.timing = timing;
        this.digger = (button == Button.ATTACK && block != null) ? new BlockDigger(player) : null;
    }

    // ---- factories (default timings; overloads take an explicit Timing) ----

    /** Left-click a block: break it (held until gone; creative insta / survival timed). */
    public static Interaction attackBlock(AnimusPlayer p, BlockPos pos) {
        return new Interaction(p, Button.ATTACK, pos, null, InteractionHand.MAIN_HAND, Timing.hold());
    }

    /** Left-click an entity once (cooldown-gated native attack). */
    public static Interaction attackEntity(AnimusPlayer p, Entity target) {
        return attackEntity(p, target, Timing.once());
    }

    public static Interaction attackEntity(AnimusPlayer p, Entity target, Timing timing) {
        return new Interaction(p, Button.ATTACK, null, target, InteractionHand.MAIN_HAND, timing);
    }

    /** Right-click a block: place / activate with the held item (raycasts to {@code pos}). */
    public static Interaction useBlock(AnimusPlayer p, BlockPos pos, InteractionHand hand) {
        return new Interaction(p, Button.USE, pos, null, hand, Timing.once());
    }

    /** Right-click a pre-resolved block hit — placement / precise activation supplies
     *  the exact support face, so this skips the raycast and presses against {@code hit}. */
    public static Interaction useBlock(AnimusPlayer p, BlockHitResult hit, InteractionHand hand) {
        Interaction i = new Interaction(p, Button.USE, hit.getBlockPos(), null, hand, Timing.once());
        i.presetHit = hit;
        return i;
    }

    /** Right-click an entity: trade / breed / mount / name. */
    public static Interaction useEntity(AnimusPlayer p, Entity target, InteractionHand hand) {
        return new Interaction(p, Button.USE, null, target, hand, Timing.once());
    }

    /** Right-click in the air with the held item, on the given {@link Timing}
     *  ({@code hold()} eats food / {@code hold(n)} draws and looses a bow). */
    public static Interaction useInAir(AnimusPlayer p, InteractionHand hand, Timing timing) {
        return new Interaction(p, Button.USE, null, null, hand, timing);
    }

    public String failReason() {
        return failReason;
    }

    public Status tick() {
        if (button == Button.ATTACK && block != null) {
            return breakBlock();                       // inherently continuous
        }
        if (button == Button.USE && block == null && entity == null) {
            return useAir();
        }
        return discrete();                             // attack entity / use block / use entity
    }

    // ---- ATTACK + block: continuous break ----

    private Status breakBlock() {
        if (player.level().getBlockState(block).isAir()) return Status.DONE;
        return digger.dig(block) ? Status.DONE : Status.RUNNING;
    }

    // ---- USE + air: tap or hold (food / bow) ----

    private Status useAir() {
        InputDriver.halt(player);
        if (!started) {
            started = true;
            player.gameMode.useItem(player, player.level(), player.getItemInHand(hand), hand);
            if (!timing.hold) return Status.DONE;      // single tap (throw / instant use)
        }
        if (!player.isUsingItem()) return Status.DONE; // e.g. food finished eating
        if (timing.maxHold > 0 && ++held >= timing.maxHold) {
            player.releaseUsingItem();                 // e.g. loose the bow
            return Status.DONE;
        }
        return Status.RUNNING;
    }

    // ---- discrete: attack entity / use block / use entity (once or repeat) ----

    private Status discrete() {
        if (cooldown > 0) {
            cooldown--;
            return Status.RUNNING;
        }
        boolean fired = switch (button) {
            case ATTACK -> fireAttackEntity();
            case USE -> entity != null ? fireUseEntity() : fireUseBlock();
        };
        if (hardFail) return Status.FAILED;
        if (!fired) return Status.RUNNING;             // soft wait (attack cooldown not ready)
        if (++fires >= timing.limit) return Status.DONE;
        cooldown = timing.interval;
        return Status.RUNNING;
    }

    private boolean fireAttackEntity() {
        if (entity == null || !entity.isAlive()) return false;
        InputDriver.halt(player);
        InputDriver.lookAt(player, entity.getEyePosition());
        if (player.getAttackStrengthScale(0.0f) < 0.95f) {
            return false;                              // wait out the attack cooldown
        }
        player.attack(entity);                         // native damage / cooldown / sweep / knockback
        player.resetAttackStrengthTicker();
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private boolean fireUseBlock() {
        InputDriver.halt(player);
        BlockHitResult hit;
        if (presetHit != null) {
            hit = presetHit;                                  // caller already resolved the support face
            InputDriver.lookAt(player, hit.getLocation());
        } else {
            InputDriver.lookAt(player, Vec3.atCenterOf(block));
            hit = raycastBlock();
            if (hit == null) {
                failReason = "can't see the block to use (out of reach or line of sight blocked)";
                hardFail = true;
                return false;
            }
        }
        for (InteractionHand h : HANDS) {
            InteractionResult res = player.gameMode.useItemOn(
                    player, player.level(), player.getItemInHand(h), h, hit);
            if (res.consumesAction()) {
                player.swing(h);
                return true;
            }
        }
        // Nothing consumed (e.g. empty hand on a non-interactive block) — still a press.
        return true;
    }

    private boolean fireUseEntity() {
        if (entity == null || !entity.isAlive()) {
            failReason = "the entity is gone";
            hardFail = true;
            return false;
        }
        InputDriver.halt(player);
        InputDriver.lookAt(player, entity.getEyePosition());
        // entity-RELATIVE hit point (only interactAt overrides like armor stands read it).
        Vec3 rel = new Vec3(0.0, entity.getBbHeight() * 0.5, 0.0);
        for (InteractionHand h : HANDS) {
            if (entity.interact(player, h, rel).consumesAction()) {       // animals / villagers
                return true;
            }
            if (player.interactOn(entity, h, rel).consumesAction()) {     // item frames / leads
                return true;
            }
        }
        return true;   // a press with no effect is still a press
    }

    /** Raycast from the eyes along the current look; the hit must be the target block. */
    private BlockHitResult raycastBlock() {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        Vec3 end = eye.add(look.x * REACH, look.y * REACH, look.z * REACH);
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block)) {
            return hit;
        }
        return null;
    }

    /** Abandon any in-progress interaction (clears a dig overlay / releases a held use). */
    public void stop() {
        if (digger != null) digger.cancel();
        if (player.isUsingItem()) player.releaseUsingItem();
        InputDriver.halt(player);
    }
}
