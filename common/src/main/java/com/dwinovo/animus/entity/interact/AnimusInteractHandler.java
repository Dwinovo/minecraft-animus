package com.dwinovo.animus.entity.interact;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.init.InitTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Central dispatcher for every right-click on an {@link AnimusEntity}.
 *
 * <h2>State table</h2>
 * <pre>
 * untamed       + food                                   → {@link #handleTame}    (30% chance, consume 1)
 * tamed + owner + food                                   → {@link #handleFeed}    (+4 HP if not max)
 * tamed + owner + sneak + main hand                      → {@link #handleOpenChooser} (model GUI)
 * tamed + owner + main hand + no food + not sneaking     → {@link #handleOpenPrompt}  (LLM prompt GUI)
 * otherwise                                              → PASS (vanilla mob interact)
 * </pre>
 *
 * <p>Non-owners can never tame an already-tamed Animus, can never open its
 * GUI, and cannot heal it — feeding food to a tamed Animus you do not own
 * is a no-op. This is the same protection wolves give: once bonded, only
 * the owner manipulates state.
 *
 * <h2>Result semantics</h2>
 * Each handler returns {@link InteractionResult#SUCCESS} on the client side
 * (so the arm swing plays, the player feels the click "landed") and
 * {@link InteractionResult#CONSUME} on the server (so vanilla doesn't try
 * to do anything else with the held item afterward).
 *
 * <p>{@link InteractionResult#PASS} means "we didn't handle it" — control
 * flows back to {@link AnimusEntity#mobInteract} which delegates to
 * {@code super.mobInteract()} for vanilla fall-through behavior (e.g.
 * leashing).
 */
public final class AnimusInteractHandler {

    /**
     * Probability a single right-click with food tames an untamed Animus.
     * Matches vanilla wolf (~1/3 per bone) and chiikawa's pet value.
     */
    private static final float TAME_CHANCE = 0.30f;

    /** HP restored per feed action — equals 2 hearts. */
    private static final float FEED_HEAL = 4.0f;

    private AnimusInteractHandler() {}

    public static InteractionResult handle(AnimusEntity entity, Player player, InteractionHand hand) {
        Level level = entity.level();
        ItemStack held = player.getItemInHand(hand);
        boolean isFood = held.is(InitTag.TAME_FOODS);
        boolean isTame = entity.isTame();
        boolean isOwner = entity.isOwnedBy(player);
        boolean isSneaking = player.isShiftKeyDown();

        if (!isTame && isFood) {
            return handleTame(level, entity, player, hand);
        }
        if (isTame && isOwner && isFood) {
            return handleFeed(level, entity, player, hand);
        }
        if (isTame && isOwner && hand == InteractionHand.MAIN_HAND) {
            if (isSneaking) {
                return handleOpenChooser(level, entity);
            }
            return handleOpenPrompt(level, entity);
        }
        return InteractionResult.PASS;
    }

    private static InteractionResult handleTame(Level level, AnimusEntity entity,
                                                Player player, InteractionHand hand) {
        if (!level.isClientSide()) {
            if (!player.isCreative()) {
                player.getItemInHand(hand).shrink(1);
            }
            if (level.getRandom().nextFloat() < TAME_CHANCE) {
                entity.tame(player);
                // Vanilla entity event 7 = heart particle burst; the client
                // renderer picks this up in TamableAnimal.handleEntityEvent.
                level.broadcastEntityEvent(entity, (byte) 7);
            } else {
                // Event 6 = smoke puff — same code path as wolf taming failures.
                level.broadcastEntityEvent(entity, (byte) 6);
            }
        }
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
    }

    private static InteractionResult handleFeed(Level level, AnimusEntity entity,
                                                Player player, InteractionHand hand) {
        if (entity.getHealth() >= entity.getMaxHealth()) {
            // At full HP — don't waste the food. PASS so vanilla mob interact
            // gets a turn (no-op for our entity, but lets future leashing /
            // saddle-style interactions try.)
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            if (!player.isCreative()) {
                player.getItemInHand(hand).shrink(1);
            }
            entity.heal(FEED_HEAL);
        }
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
    }

    private static InteractionResult handleOpenChooser(Level level, AnimusEntity entity) {
        if (level.isClientSide()) {
            // Client-only screen class; the dedicated server JVM never sees this.
            com.dwinovo.animus.client.screen.ChooseModelScreen.open(entity);
        }
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult handleOpenPrompt(Level level, AnimusEntity entity) {
        if (level.isClientSide()) {
            // Client-only screen class; dedicated server never loads it.
            com.dwinovo.animus.client.screen.PromptScreen.open(entity);
        }
        return InteractionResult.SUCCESS;
    }
}
