package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a queued {@link TaskRecord} to the {@link CompanionTask} that runs it on
 * the player body — the seam where concrete task execution plugs into the
 * engine. The base {@code numen-api} engine ships <em>no</em> task types of its
 * own; {@code numen-core} (and any third-party tool pack) registers each record
 * type with the runner that executes it, keyed by the record's concrete class.
 *
 * <p>A record whose type was never registered falls back to
 * {@link UnsupportedCompanionTask}, which fails the task cleanly with a clear
 * message rather than crashing the tick loop.
 *
 * <h2>Registration</h2>
 * Tool packs register at mod init (on both sides — a dedicated server runs the
 * task body), e.g.:
 * <pre>{@code
 * CompanionTaskFactory.register(MoveToTaskRecord.class,
 *         (player, record) -> new MoveToCompanionTask(player, record));
 * }</pre>
 * Registration is idempotent-by-type: a later registration for the same record
 * class replaces the earlier runner.
 */
public final class CompanionTaskFactory {

    /** Builds the {@link CompanionTask} that runs a record of the registered type. */
    @FunctionalInterface
    public interface Runner<R extends TaskRecord> {
        CompanionTask create(NumenPlayer player, R record);
    }

    private static final Map<Class<? extends TaskRecord>, Runner<? extends TaskRecord>> RUNNERS =
            new ConcurrentHashMap<>();

    private CompanionTaskFactory() {}

    /** Register the runner for a concrete record type. Tick-thread + init safe. */
    public static <R extends TaskRecord> void register(Class<R> type, Runner<R> runner) {
        RUNNERS.put(type, runner);
    }

    /** How many record types are currently registered. */
    public static int size() {
        return RUNNERS.size();
    }

    @SuppressWarnings("unchecked")
    public static CompanionTask create(NumenPlayer player, TaskRecord record) {
        Runner<TaskRecord> runner = (Runner<TaskRecord>) RUNNERS.get(record.getClass());
        return runner != null ? runner.create(player, record) : new UnsupportedCompanionTask(record);
    }
}
