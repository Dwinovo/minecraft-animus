package com.dwinovo.animus.task;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable descriptor of an in-flight task. The {@link com.dwinovo.animus.task.tool.AnimusTool tool layer}
 * builds one record per LLM {@code tool_call} and enqueues it; the matching
 * {@link LlmTaskGoal} picks it up, drives lifecycle, and writes a
 * {@link TaskResult} back before completion.
 *
 * <h2>Type pattern</h2>
 * Concrete subclasses (e.g. {@code MoveToTaskRecord}) carry the typed input
 * parameters as final fields. {@link LlmTaskGoal} is generic over the
 * subclass type and {@link Class#cast}s the queue head against the
 * registered class — no reflection at runtime, just one {@code instanceof}
 * check at the dispatch boundary.
 *
 * <h2>Threading</h2>
 * Records are constructed off-tick (in the LLM async callback) and read on
 * the server tick thread. The "construct off-tick" is followed by a hop
 * through {@code server.execute(...)} into the tick thread before the record
 * is enqueued, so the happens-before is established by the executor's queue —
 * no fields need to be {@code volatile}.
 *
 * <h2>Why not a record (Java {@code record} keyword)</h2>
 * State transitions ({@link TaskState}, {@link TaskResult}) need to be
 * mutable. Subclass-style {@code class} fits.
 */
public abstract class TaskRecord {

    private static final AtomicLong ID_SOURCE = new AtomicLong();

    /** Monotonically increasing internal id; only used for logging / dedup. */
    private final long id;
    /** Stable name of the originating tool (matches {@code AnimusTool.name()}). */
    private final String toolName;
    /**
     * The {@code id} field from the LLM's {@code tool_call} — must be echoed
     * verbatim in the {@code tool_call_id} of the role:tool response, or the
     * upstream API responds 400.
     */
    private final String toolCallId;
    /**
     * Game-tick (level.getGameTime()) at which this record times out. Computed
     * once at construction so {@code /tick freeze} / {@code /tick rate} change
     * are accounted for automatically (gameTime is freeze-aware).
     */
    private final long deadlineGameTime;

    private TaskState state = TaskState.PENDING;
    private TaskResult result;

    protected TaskRecord(String toolName, String toolCallId, long deadlineGameTime) {
        this.id = ID_SOURCE.incrementAndGet();
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.deadlineGameTime = deadlineGameTime;
    }

    public final long getId() { return id; }
    public final String getToolName() { return toolName; }
    public final String getToolCallId() { return toolCallId; }
    public final long getDeadlineGameTime() { return deadlineGameTime; }
    public final TaskState getState() { return state; }
    public final TaskResult getResult() { return result; }

    /** Called by {@link LlmTaskGoal} as the record transitions through lifecycle. */
    public final void setState(TaskState state) { this.state = state; }
    public final void setResult(TaskResult result) { this.result = result; }
}
