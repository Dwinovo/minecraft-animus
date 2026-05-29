package com.dwinovo.animus.entity.interact;

import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

/**
 * Right-click dispatcher for {@link AnimusEntity}. In the single-layer agent
 * design each Animus has its own conversation, so an owner main-hand
 * right-click opens that entity's chat GUI ({@code EntityChatScreen}).
 *
 * <p>Non-owners, off-hand clicks, and anything else fall through with
 * {@link InteractionResult#PASS} so vanilla mob behaviour (e.g. leashing)
 * still works.
 *
 * <p>Unit naming / model selection lives in the {@code AnimusManagerScreen}
 * Units tab (default keybind {@code X}).
 */
public final class AnimusInteractHandler {

    private AnimusInteractHandler() {}

    public static InteractionResult handle(AnimusEntity entity, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!entity.isTame() || !entity.isOwnedBy(player)) return InteractionResult.PASS;

        if (entity.level().isClientSide()) {
            openChat(entity);
        }
        // SUCCESS on both sides keeps client/server interaction in lockstep and
        // swallows the swing so the owner doesn't punch their own unit.
        return InteractionResult.SUCCESS;
    }

    /**
     * Isolated client-only call. Referencing the {@code Screen} subclass from
     * a separate method keeps the dedicated-server class loader from touching
     * client GUI classes — {@link #handle} only invokes this inside the
     * {@code isClientSide()} guard.
     */
    private static void openChat(AnimusEntity entity) {
        com.dwinovo.animus.client.screen.EntityChatScreen.open(entity);
    }
}
