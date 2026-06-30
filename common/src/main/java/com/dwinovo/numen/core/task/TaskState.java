package com.dwinovo.numen.core.task;

/**
 * Lifecycle state of a {@link TaskRecord}.
 *
 * <p>Transitions are tick-driven and one-way: a record starts at
 * {@link #PENDING}, {@code CompanionTickDispatcher} flips it to
 * {@link #RUNNING} when it picks the record up, and lands on exactly one of
 * the four terminal states ({@link #SUCCESS}, {@link #FAILED}, {@link #TIMEOUT},
 * {@link #CANCELLED}) once the running {@code CompanionTask} reaches a terminal
 * state (or the deadline / a cancel forces one).
 *
 * <p>The agent loop only ever observes terminal states (it drains the queue's
 * outbox), so callers outside the dispatcher don't need to handle
 * {@code PENDING} / {@code RUNNING} explicitly.
 */
public enum TaskState {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    CANCELLED;

    public boolean isTerminal() {
        return this != PENDING && this != RUNNING;
    }
}
