package com.dwinovo.animus.task;

import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Abstract base for every atomic-task {@link Goal}. Bridges the
 * Goal-lifecycle (canUse / start / tick / canContinueToUse / stop) onto the
 * TaskRecord lifecycle (PENDING → RUNNING → terminal).
 *
 * <h2>Lifecycle mapping</h2>
 * <pre>
 *   queue.enqueue(record)        ← off-tick, then server.execute(...) hop
 *      ↓ next tick
 *   GoalSelector.canUse()        ← we peek the queue, return true if head matches
 *   GoalSelector.start()         ← we poll the queue, flip state to RUNNING, onStart
 *   GoalSelector.tick()  *N      ← we tick deadline + delegate to onTick
 *   GoalSelector.continue()      ← we return state == RUNNING
 *   GoalSelector.stop()          ← we buildResult, push to outbox
 *      ↓ next agent-loop poll
 *   queue.drainCompleted() → LLM
 * </pre>
 *
 * <h2>Serial execution</h2>
 * All LLM tasks run serially: {@link TaskQueue} is a single FIFO and every
 * {@code LlmTaskGoal.canUse()} peeks the same head, so only the goal whose
 * {@code toolName} matches the head wins — the rest see a non-matching peek
 * and stay idle until the head completes. No {@code setFlags(...)} is needed
 * for that, and we don't register any vanilla pathfinding/look goals
 * alongside (see {@code AnimusEntity.registerGoals}). Priority is identical
 * across all LLM tasks (all at {@code addGoal(0, ...)}), which neutralises
 * vanilla's preemption mechanic.
 *
 * <h2>What subclasses implement</h2>
 * Just three methods:
 * <ul>
 *   <li>{@link #onStart} — kick off the world-mutating side of the task
 *       (e.g. {@code navigation.moveTo}). Set state to a terminal state here
 *       if it's impossible to even start (the {@link #tick} call won't be
 *       made if state is already terminal).</li>
 *   <li>{@link #onTick} — check completion / failure each tick; mutate state
 *       to a terminal value when done. Deadline timeout is handled by the
 *       base class, so don't re-implement that.</li>
 *   <li>{@link #buildResult} — produce the {@link TaskResult} envelope from
 *       the final state and any data scraped from the world.</li>
 * </ul>
 *
 * <h2>Why {@code requiresUpdateEveryTick = true}</h2>
 * Vanilla defaults {@link #tick} to every-other-tick (see
 * {@code Mob.serverAiStep:712}). For pathfinding-heavy goals the 100ms granularity
 * is fine; for our task system we want consistent-cadence deadline checks and
 * predictable result latency, so we opt in to the per-tick rate.
 *
 * @param <T> concrete record subtype this goal handles (matches by
 *            {@link #recordClass} via {@code instanceof}, no reflection)
 */
public abstract class LlmTaskGoal<T extends TaskRecord> extends Goal {

    protected final AnimusEntity entity;
    protected final String toolName;
    protected final Class<T> recordClass;

    /** Active record while the goal is running. {@code null} between cycles. */
    protected T currentRecord;

    protected LlmTaskGoal(AnimusEntity entity, String toolName, Class<T> recordClass) {
        this.entity = entity;
        this.toolName = toolName;
        this.recordClass = recordClass;
    }

    @Override
    public final boolean canUse() {
        TaskRecord head = entity.getTaskQueue().peekMatching(toolName);
        return head != null && recordClass.isInstance(head);
    }

    @Override
    public final void start() {
        TaskRecord polled = entity.getTaskQueue().pollMatching(toolName);
        if (polled == null || !recordClass.isInstance(polled)) {
            // Lost the race somehow; nothing to do.
            currentRecord = null;
            return;
        }
        currentRecord = recordClass.cast(polled);
        currentRecord.setState(TaskState.RUNNING);
        onStart(currentRecord);
    }

    @Override
    public final void tick() {
        if (currentRecord == null) return;
        if (currentRecord.getState() != TaskState.RUNNING) return;

        // Deadline check first — task-specific tick should never override a
        // pending timeout. Using level().getGameTime() (server-side, freeze
        // and tick-rate aware).
        if (entity.level().getGameTime() >= currentRecord.getDeadlineGameTime()) {
            currentRecord.setState(TaskState.TIMEOUT);
            return;
        }
        onTick(currentRecord);
    }

    @Override
    public final boolean canContinueToUse() {
        return currentRecord != null && currentRecord.getState() == TaskState.RUNNING;
    }

    @Override
    public final void stop() {
        if (currentRecord == null) return;
        TaskState finalState = currentRecord.getState();
        if (finalState == TaskState.PENDING || finalState == TaskState.RUNNING) {
            // External cancellation (e.g. GoalSelector evicted us). Surface
            // as CANCELLED so the LLM understands the action was interrupted.
            finalState = TaskState.CANCELLED;
            currentRecord.setState(finalState);
        }
        TaskResult result = buildResult(currentRecord, finalState);
        currentRecord.setResult(result);
        entity.getTaskQueue().complete(currentRecord);
        currentRecord = null;
    }

    @Override
    public final boolean requiresUpdateEveryTick() {
        return true;
    }

    /** First-tick world-mutation hook. Set a terminal state to abort immediately. */
    protected abstract void onStart(T record);

    /** Per-tick progress hook. Set a terminal state to finish the task. */
    protected abstract void onTick(T record);

    /** Produce the result envelope handed back to the LLM. */
    protected abstract TaskResult buildResult(T record, TaskState finalState);
}
