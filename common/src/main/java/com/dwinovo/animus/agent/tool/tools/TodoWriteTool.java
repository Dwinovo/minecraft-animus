package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan-tracking tool the LLM uses to keep a structured todo list for the
 * current task chain. Pure client-side bookkeeping — the tool's "execution"
 * is just echoing the canonicalised todo list back as the {@code role:tool}
 * reply. The LLM reads it back from conversation history on subsequent
 * turns and updates it by calling {@code todowrite} again with a new list.
 *
 * <h2>Why this works at all</h2>
 * Verified directly against opencode (anomalyco/opencode
 * {@code packages/opencode/src/tool/todo.ts} + {@code todowrite.txt}):
 * opencode's "the agent automatically plans" UX comes <em>entirely</em>
 * from the {@link #description} field below + the schema. There is no
 * additional system-prompt scaffolding, no per-turn reminder injection —
 * the tool description itself is the planning prompt, and the tool output
 * is the plan state. LLMs trained on this convention reach for it
 * unprompted once it's offered.
 *
 * <h2>Why no persistence</h2>
 * The conversation already holds it. As long as {@code ConvoState} keeps
 * the {@code role:tool} message in history, the LLM sees the latest todo
 * list on every subsequent turn. Restarting the chain wipes both — which
 * matches the player's mental model ("new prompt = fresh planning").
 *
 * @see com.dwinovo.animus.agent.tool.AnimusTool
 */
public final class TodoWriteTool implements AnimusTool {

    public static final String TOOL_NAME = "todowrite";

    private static final Set<String> ALLOWED_STATUSES =
            Set.of("pending", "in_progress", "completed", "cancelled");
    private static final Set<String> ALLOWED_PRIORITIES =
            Set.of("high", "medium", "low");

    @Override
    public String name() {
        return TOOL_NAME;
    }

    /**
     * Description is the planning prompt itself — kept long on purpose,
     * mirrors the strongest parts of opencode's {@code todowrite.txt} so
     * any model trained on that pattern reaches for the tool without extra
     * system-prompt nudging.
     */
    @Override
    public String description() {
        return """
                Create and maintain a structured task list for the current Animus session. Tracks progress, organizes multi-step work, and lets you keep one step in_progress at a time.

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

    /**
     * OpenAI structured-output friendly schema. {@code required} lists every
     * property because OpenAI strict mode rejects partial requireds.
     */
    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> todoItem = new LinkedHashMap<>();
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("content", Map.of("type", "string",
                "description", "Brief description of the step."));
        itemProps.put("status", Map.of("type", "string",
                "description", "One of: pending, in_progress, completed, cancelled.",
                "enum", List.of("pending", "in_progress", "completed", "cancelled")));
        itemProps.put("priority", Map.of("type", "string",
                "description", "One of: high, medium, low.",
                "enum", List.of("high", "medium", "low")));
        todoItem.put("type", "object");
        todoItem.put("properties", itemProps);
        todoItem.put("required", List.of("content", "status", "priority"));
        todoItem.put("additionalProperties", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("todos", Map.of(
                "type", "array",
                "description", "The complete updated todo list (replaces the previous one).",
                "items", todoItem));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("todos"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        // Unused — local tool never enqueues a TaskRecord. Required by the
        // interface; return a small positive value to keep linters happy.
        return 1L;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public String executeLocal(JsonObject args) {
        if (args == null || !args.has("todos")) {
            throw new IllegalArgumentException("missing required argument: todos");
        }
        JsonElement todosEl = args.get("todos");
        if (!todosEl.isJsonArray()) {
            throw new IllegalArgumentException("'todos' must be an array");
        }
        JsonArray todos = todosEl.getAsJsonArray();

        // Validate every entry — we want to fail loud and tell the LLM
        // exactly which item is malformed, not silently accept garbage.
        int inProgressCount = 0;
        for (int i = 0; i < todos.size(); i++) {
            JsonElement el = todos.get(i);
            if (!el.isJsonObject()) {
                throw new IllegalArgumentException("todos[" + i + "] must be an object");
            }
            JsonObject item = el.getAsJsonObject();
            requireString(item, "content", i);
            String status = requireString(item, "status", i);
            String priority = requireString(item, "priority", i);
            if (!ALLOWED_STATUSES.contains(status)) {
                throw new IllegalArgumentException(
                        "todos[" + i + "].status must be one of " + ALLOWED_STATUSES + ", got: " + status);
            }
            if (!ALLOWED_PRIORITIES.contains(priority)) {
                throw new IllegalArgumentException(
                        "todos[" + i + "].priority must be one of " + ALLOWED_PRIORITIES + ", got: " + priority);
            }
            if ("in_progress".equals(status)) inProgressCount++;
        }

        // Echo back the canonical JSON. The LLM reads this on the next turn
        // as a role:tool message — that's how it sees its own latest plan.
        // We deliberately re-serialise via Gson so the formatting is consistent
        // regardless of how the LLM whitespaced the original.
        String echoed = todos.toString();

        if (inProgressCount > 1) {
            // Not fatal — opencode also doesn't reject this, only warns via prompt
            // discipline. We surface it in the reply so the LLM self-corrects.
            return "{\"success\":true,\"warning\":\"more than one todo in_progress ("
                    + inProgressCount + "); keep exactly one\",\"todos\":" + echoed + "}";
        }
        return "{\"success\":true,\"todos\":" + echoed + "}";
    }

    private static String requireString(JsonObject obj, String key, int index) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            throw new IllegalArgumentException("todos[" + index + "]." + key + " is required");
        }
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("todos[" + index + "]." + key + " must be a string");
        }
    }
}
