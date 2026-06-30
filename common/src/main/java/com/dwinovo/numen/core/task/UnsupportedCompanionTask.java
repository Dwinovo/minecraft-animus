package com.dwinovo.numen.core.task;
import com.dwinovo.numen.task.TaskResult;

/**
 * Placeholder for a tool whose executor hasn't been ported to the player body
 * yet. Fails with a clear message so the LLM gets a real answer instead of a
 * hang while the migration is in progress.
 */
@com.dwinovo.numen.api.Internal
public final class UnsupportedCompanionTask implements CompanionTask {

    private final String toolName;

    public UnsupportedCompanionTask(TaskRecord record) {
        this.toolName = record.getToolName();
    }

    @Override
    public void start() {
        // nothing — fails on the first tick
    }

    @Override
    public TaskState tick() {
        return TaskState.FAILED;
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        return TaskResult.fail(toolName + " is not available on the companion yet "
                + "(it's mid-migration to the new player body)");
    }
}
