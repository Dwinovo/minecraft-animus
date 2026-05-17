package com.dwinovo.animus.agent.tool;

/**
 * Which agent is calling the tool. Decides:
 * <ul>
 *   <li>Whether the tool is even visible to that agent's LLM (via
 *       {@link AnimusTool#allowedRoles()} filtering at registry level).</li>
 *   <li>Which "self" perspective the tool sees through
 *       {@link ClientToolContext}: an {@code AnimusEntity} for
 *       {@link #ENTITY}, the local {@link net.minecraft.world.entity.player.Player}
 *       for {@link #PLAYER}.</li>
 * </ul>
 */
public enum AgentRole {
    /** The per-player "brain" — has only assign/recall/perception/planning. */
    PLAYER,
    /** A per-Animus "body" — has the full world-action + perception toolset. */
    ENTITY
}
