package com.dwinovo.animus.task;

/**
 * One running task on a companion {@link com.dwinovo.animus.entity.AnimusPlayer}
 * body, driven by {@code CompanionTickDispatcher}. The player-body replacement
 * for the Mob's {@code LlmTaskGoal} (which was a vanilla {@code Goal} run by a
 * GoalSelector) — here the dispatcher owns the lifecycle directly:
 * {@link #start()} once, {@link #tick()} each server tick until it returns a
 * terminal {@link TaskState}, then {@link #buildResult} for the reply.
 */
public interface CompanionTask {

    /** First-tick setup. May return a terminal state immediately via the record. */
    void start();

    /** Advance one tick. Returns {@link TaskState#RUNNING} or a terminal state. */
    TaskState tick();

    /** The result envelope handed back to the LLM. */
    TaskResult buildResult(TaskState finalState);
}
