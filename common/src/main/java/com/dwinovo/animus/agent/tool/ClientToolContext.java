package com.dwinovo.animus.agent.tool;

import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Per-turn context handed to local tools. Carries the agent role plus the
 * "anchor" entity for that role — for {@link AgentRole#ENTITY} that's the
 * {@code AnimusEntity}, for {@link AgentRole#PLAYER} that's the local
 * {@link Player}. Tools that share implementation across roles
 * ({@code scan_nearby_entities}, {@code inspect_block}, {@code get_world_info})
 * just call {@link #anchor()} to get the perspective centre.
 *
 * <p>Either the entity or the player may be {@code null} when the
 * corresponding side is mid-transition (entity unloaded out of view
 * distance, no local player yet). Perception tools must check and surface
 * a clean failure rather than NPE.
 */
public record ClientToolContext(
        AgentRole role,
        AnimusEntity entity,
        Player player,
        int unitId,
        int vanillaEntityId) {

    /** Factory for the EntityAgent path — entity & vanilla id known. */
    public static ClientToolContext forEntity(int vanillaEntityId, AnimusEntity entity, int unitId) {
        return new ClientToolContext(AgentRole.ENTITY, entity, null, unitId, vanillaEntityId);
    }

    /** Factory for a player-anchored path — only the player is meaningful. */
    public static ClientToolContext forPlayer(Player player) {
        return new ClientToolContext(AgentRole.PLAYER, null, player, -1, -1);
    }

    /**
     * "Self" perspective:
     * <ul>
     *   <li>{@link AgentRole#ENTITY} → the Animus body (its position is the
     *       scan center, its inventory is the held-item check).</li>
     *   <li>{@link AgentRole#PLAYER} → the player (its position is the
     *       scan center, its inventory is the held-item check).</li>
     * </ul>
     * May be {@code null} if the corresponding entity isn't currently
     * loaded — tools should check.
     */
    public LivingEntity anchor() {
        return role == AgentRole.ENTITY ? entity : player;
    }
}
