package com.dwinovo.numen.core.task;
import com.dwinovo.numen.task.TaskResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Per-entity FIFO queue of {@link TaskRecord}s, plus an outbox of completed
 * records awaiting agent-loop pickup. Serial by design —
 * {@code CompanionTickDispatcher} drives one record at a time (head-first), so
 * there is at most one running task per body.
 *
 * <h2>Threading model</h2>
 * Every operation is called from the server main thread:
 * <ul>
 *   <li>{@link #enqueue} runs from {@code ExecuteToolPayload.handle} —
 *       the C→S packet handler is delivered on the server main thread by the
 *       network layer (see {@code ExecuteToolPayload.handle}'s javadoc).</li>
 *   <li>{@link #pollHead}, {@link #complete}, {@link #cancelAll} run from
 *       {@code CompanionTickDispatcher} as it picks up, finishes, and cancels
 *       the head task each tick.</li>
 *   <li>{@link #drainCompleted} runs from the companion tick dispatcher
 *       once per tick, shipping completed records back to the owning player as
 *       {@code TaskResultPayload} for the client-side agent loop to consume.</li>
 * </ul>
 * Plain non-thread-safe collections are deliberate — adding {@code synchronized}
 * or {@code ConcurrentLinkedDeque} would only mask a missed thread hop.
 */
@com.dwinovo.numen.api.Internal
public final class TaskQueue {

    private final Deque<TaskRecord> pending = new ArrayDeque<>();
    private final Deque<TaskRecord> completed = new ArrayDeque<>();

    public void enqueue(TaskRecord record) {
        pending.addLast(record);
    }

    /** Remove and return the first pending record (FIFO). Called from {@code CompanionTickDispatcher}. */
    public TaskRecord pollHead() {
        var it = pending.iterator();
        while (it.hasNext()) {
            TaskRecord r = it.next();
            if (r.getState() == TaskState.PENDING) {
                it.remove();
                return r;
            }
        }
        return null;
    }

    /** Move a record from in-flight to the outbox. Called from {@code CompanionTickDispatcher}. */
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
