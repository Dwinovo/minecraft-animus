package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

/** Client-local tool (raw NumenTool): maintain the agent's structured task list. */
public final class TodoWriteTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final AgentTools impl = new AgentTools();

    private record Args(List<AgentTools.Todo> todos) {}

    @Override
    public String name() {
        return "todowrite";
    }

    @Override
    public String description() {
        return """
                Create and maintain a structured task list for the current Numen session. Tracks progress, organizes multi-step work, and lets you keep one step in_progress at a time.

                ## When to use
                Use proactively when:
                - The task requires 3+ distinct steps or actions (not just 3 tool calls for a single conceptual step)
                - The work is non-trivial and benefits from planning
                - The user provides multiple sub-tasks
                - You start a step — mark it `in_progress` (only one at a time) before working
                - You finish a step — mark it `completed` and add any follow-ups discovered during the work

                ## When NOT to use
                Skip when:
                - The work is a single straightforward action (or <3 trivial steps)
                - The request is purely conversational ("hi", "look at me")
                - Tracking adds no organizational value

                ## States
                - `pending` — not started
                - `in_progress` — actively working (exactly ONE at a time)
                - `completed` — finished successfully (only after the work is actually done, never based on intent)
                - `cancelled` — no longer needed

                ## Rules
                - Update status in real time; don't batch completions
                - Mark `completed` only after the actual work is done
                - Keep exactly one `in_progress` while work remains
                - If blocked or partial, keep it `in_progress` and add a follow-up todo describing the blocker

                When in doubt, use it.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .objectArray("todos", "The complete updated todo list (replaces the previous one).", item -> item
                        .string("content", "Brief description of the step.")
                        .enumStr("status", "One of: pending, in_progress, completed, cancelled.",
                                "pending", "in_progress", "completed", "cancelled")
                        .enumStr("priority", "One of: high, medium, low.", "high", "medium", "low"))
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            Args a = GSON.fromJson(call.rawArgs(), Args.class);
            call.complete(impl.todowrite(a.todos()));
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
