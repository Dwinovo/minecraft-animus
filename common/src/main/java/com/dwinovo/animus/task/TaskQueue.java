package com.dwinovo.animus.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Per-entity FIFO queue of {@link TaskRecord}s, plus an outbox of completed
 * records awaiting agent-loop pickup.
 *
 * <h2>Threading model</h2>
 * Every operation is called from the server main thread:
 * <ul>
 *   <li>{@link #enqueue} runs from {@code ExecuteToolPayload.handle} —
 *       the C→S packet handler is delivered on the server main thread by the
 *       network layer (see {@code ExecuteToolPayload.handle}'s javadoc).</li>
 *   <li>{@link #peekMatching}, {@link #pollMatching}, {@link #complete} all
 *       run from {@code GoalSelector.tick} (canUse / start / stop) via the
 *       matching {@link LlmTaskGoal}.</li>
 *   <li>{@link #drainCompleted} runs from {@code AnimusEntity.customServerAiStep}
 *       once per tick, shipping completed records back to the owning player as
 *       {@code TaskResultPayload} for the client-side agent loop to consume.</li>
 * </ul>
 * Plain non-thread-safe collections are deliberate — adding {@code synchronized}
 * or {@code ConcurrentLinkedDeque} would only mask a missed thread hop.
 *
 * <h2>Why peek + poll instead of poll alone</h2>
 * Vanilla's {@code GoalSelector.canUse()} is non-mutating by contract — it
 * gets called twice per goal-evaluation cycle and shouldn't dequeue work that
 * a later flag-conflict check might reject. We peek in {@code canUse} and
 * only commit (poll) in {@code start}.
 *
 * <h2>Why match by tool name</h2>
 * Each atomic-task Goal subclass registers for one tool name. When a record
 * sits at the head with a tool name that no registered Goal handles, vanilla
 * never picks it up — it'd block the queue indefinitely. The agent loop
 * intercepts unknown tool names at parse time (before they ever become
 * records), but the matching API guards against the corner case where a
 * Goal is removed mid-game.
 */
public final class TaskQueue {

    private final Deque<TaskRecord> pending = new ArrayDeque<>();
    private final Deque<TaskRecord> completed = new ArrayDeque<>();

    public void enqueue(TaskRecord record) {
        pending.addLast(record);
    }

    /**
     * Return the first pending record whose tool name matches {@code toolName},
     * without removing it. Returns {@code null} if no match.
     *
     * <p>For MVP scans linearly — there is at most one record per channel per
     * tool name in flight (Goal exclusivity guarantees this), so the queue is
     * effectively small.
     */
    public TaskRecord peekMatching(String toolName) {
        for (TaskRecord r : pending) {
            if (r.getState() == TaskState.PENDING && toolName.equals(r.getToolName())) {
                return r;
            }
        }
        return null;
    }

    /**
     * Remove and return the first pending record matching {@code toolName}.
     * Called from {@code Goal.start}.
     */
    public TaskRecord pollMatching(String toolName) {
        var it = pending.iterator();
        while (it.hasNext()) {
            TaskRecord r = it.next();
            if (r.getState() == TaskState.PENDING && toolName.equals(r.getToolName())) {
                it.remove();
                return r;
            }
        }
        return null;
    }

    /** Move a record from in-flight to the outbox. Called from {@code Goal.stop}. */
    public void complete(TaskRecord record) {
        completed.addLast(record);
    }

    /**
     * Move all completed records out of the outbox. Called by the agent loop
     * each tick to feed tool results back to the LLM.
     */
    public List<TaskRecord> drainCompleted() {
        if (completed.isEmpty()) return List.of();
        List<TaskRecord> out = new ArrayList<>(completed);
        completed.clear();
        return out;
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    public int pendingCount() {
        return pending.size();
    }

    /**
     * Cancel every pending record (mark TaskState.CANCELLED, move to outbox).
     * Called on entity removal / death so the agent loop can flush results
     * back to the LLM rather than leaving tool_calls unanswered.
     */
    public void cancelAll(String reason) {
        for (TaskRecord r : pending) {
            r.setState(TaskState.CANCELLED);
            r.setResult(TaskResult.cancelled(reason));
            completed.addLast(r);
        }
        pending.clear();
    }
}
