package com.dwinovo.animus.entity.interact;

import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

/**
 * Right-click dispatcher for {@link AnimusEntity}. In the multi-agent
 * architecture an Animus body is summoned and disposed by the
 * PlayerAgent — there's no per-entity GUI, so right-click is a no-op
 * (returns {@link InteractionResult#PASS} to let vanilla mob behaviour
 * fall through).
 *
 * <p>Configuration / naming / model selection happens in the
 * {@code AnimusManagerScreen} GUI (default keybind {@code X}).
 * Conversations with the PlayerAgent happen via {@code /animus <prompt>}.
 */
public final class AnimusInteractHandler {

    private AnimusInteractHandler() {}

    public static InteractionResult handle(AnimusEntity entity, Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }
}
