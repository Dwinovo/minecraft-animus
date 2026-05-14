package com.dwinovo.animus.task;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Logical execution channel for a task. Each channel maps to one or more
 * vanilla {@link Goal.Flag} values, which is the actual mutex primitive the
 * {@code GoalSelector} enforces: same-flag goals are exclusive, different-flag
 * goals run in parallel.
 *
 * <h2>Why expose this as our own enum</h2>
 * Tasks (and tool definitions) should talk in terms of "what kind of
 * action" they perform, not in vanilla's flag taxonomy — vanilla flags are
 * stable but our composite-task layer may eventually need to declare channels
 * that don't map 1:1 (e.g. a "manipulation" channel that locks both LOOK and
 * an extension flag). The indirection costs nothing and keeps the abstraction
 * portable.
 *
 * <h2>Channel set on a single task</h2>
 * A task may occupy multiple channels — e.g. a future {@code follow_entity}
 * task takes both LOCOMOTION (it moves) and LOOK (it faces the target). The
 * {@link LlmTaskGoal} resolves all occupied channels into the union of their
 * {@link Goal.Flag}s and hands that set to the GoalSelector.
 */
public enum Channel {
    /** Movement / pathfinding tasks. Locks vanilla {@code MOVE} and {@code JUMP}. */
    LOCOMOTION(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP)),
    /** Head / body rotation tasks. Locks vanilla {@code LOOK}. */
    LOOK(EnumSet.of(Goal.Flag.LOOK)),
    /** Targeted-entity tasks (combat, follow). Locks vanilla {@code TARGET}. */
    TARGET(EnumSet.of(Goal.Flag.TARGET)),
    /**
     * Chat / speech tasks. No vanilla flag — speech runs freely in parallel
     * with locomotion and look. Tasks claiming only SPEECH never block
     * vanilla AI.
     */
    SPEECH(EnumSet.noneOf(Goal.Flag.class));

    private final EnumSet<Goal.Flag> flags;

    Channel(EnumSet<Goal.Flag> flags) {
        this.flags = flags;
    }

    public EnumSet<Goal.Flag> flags() {
        return flags;
    }

    /** Compute the union of vanilla flags occupied by the given channel set. */
    public static EnumSet<Goal.Flag> unionFlags(EnumSet<Channel> channels) {
        EnumSet<Goal.Flag> result = EnumSet.noneOf(Goal.Flag.class);
        for (Channel c : channels) {
            result.addAll(c.flags);
        }
        return result;
    }
}
