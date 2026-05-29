package com.dwinovo.animus.entity.interact;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.init.InitTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Right-click dispatcher for {@link AnimusEntity}.
 *
 * <h2>Untamed</h2>
 * Feeding an item tagged {@code animus:tame_foods} has a 1-in-3 chance to
 * tame (wolf-style). Consumes one food (unless creative). Hearts on success,
 * smoke on failure — vanilla {@code TamableAnimal} renders both from the
 * broadcast entity events.
 *
 * <h2>Tamed</h2>
 * An owner main-hand right-click opens the per-entity GUI (chat / inventory /
 * model). Everyone else falls through with {@link InteractionResult#PASS} so
 * vanilla behaviour (leashing, etc.) still works.
 */
public final class AnimusInteractHandler {

    /** 1-in-N chance to tame per feed. */
    private static final int TAME_ODDS = 3;

    private AnimusInteractHandler() {}

    public static InteractionResult handle(AnimusEntity entity, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);

        if (!entity.isTame()) {
            if (held.is(InitTag.TAME_FOODS)) {
                if (!entity.level().isClientSide()) {
                    if (!player.getAbilities().instabuild) held.shrink(1);
                    if (entity.getRandom().nextInt(TAME_ODDS) == 0) {
                        entity.tame(player);
                        entity.level().broadcastEntityEvent(entity, (byte) 7);  // hearts
                    } else {
                        entity.level().broadcastEntityEvent(entity, (byte) 6);  // smoke
                    }
                }
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }

        // Tamed: owner main-hand click opens the GUI.
        if (hand == InteractionHand.MAIN_HAND && entity.isOwnedBy(player)) {
            if (entity.level().isClientSide()) {
                openGui(entity);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /**
     * Isolated client-only call — referencing the {@code Screen} subclass from
     * a separate method keeps the dedicated-server class loader from touching
     * client GUI classes.
     */
    private static void openGui(AnimusEntity entity) {
        com.dwinovo.animus.client.screen.EntityChatScreen.open(entity);
    }
}
