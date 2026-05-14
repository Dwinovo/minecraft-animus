package com.dwinovo.animus.task;

import com.google.gson.JsonObject;

import java.util.Map;

/**
 * Outcome a task hands back to the LLM agent loop. Serialised as the
 * {@code content} of a {@code role:tool} message in the next chat completion
 * request.
 *
 * <h2>Shape decision</h2>
 * Modeled after Mindcraft's {@code {success, message, timedout, interrupted}}
 * envelope (validated by their open-source agent on small models). The
 * {@code data} map carries task-specific structured info — e.g. moveTo
 * reports {@code final_x/y/z}, future scan_inventory would report
 * {@code items: [...]}. Keys are lowercase snake_case; values must be
 * Gson-serialisable.
 *
 * <p>Composite tasks (Phase-2) will use the same envelope but populate
 * {@code data.step_results} with the per-step result list, so a failed chain
 * can be traced step-by-step by the LLM.
 *
 * @param success      did the task achieve its goal? Distinct from
 *                     {@code !timedOut && !interrupted}: a moveTo can succeed,
 *                     fail (unreachable), time out, or get cancelled.
 * @param message      short human-readable summary. Visible in agent logs and
 *                     useful for the LLM to reason about what happened
 *                     ("path ended before reaching target").
 * @param timedOut     whether the terminal state was {@link TaskState#TIMEOUT}.
 * @param interrupted  whether the terminal state was {@link TaskState#CANCELLED}.
 * @param data         task-specific structured payload. Empty map for no extras.
 */
public record TaskResult(boolean success,
                         String message,
                         boolean timedOut,
                         boolean interrupted,
                         Map<String, Object> data) {

    public static TaskResult ok(String message, Map<String, Object> data) {
        return new TaskResult(true, message, false, false, data);
    }

    public static TaskResult ok(String message) {
        return new TaskResult(true, message, false, false, Map.of());
    }

    public static TaskResult fail(String message, Map<String, Object> data) {
        return new TaskResult(false, message, false, false, data);
    }

    public static TaskResult fail(String message) {
        return new TaskResult(false, message, false, false, Map.of());
    }

    public static TaskResult timeout(String message) {
        return new TaskResult(false, message, true, false, Map.of());
    }

    public static TaskResult cancelled(String message) {
        return new TaskResult(false, message, false, true, Map.of());
    }

    /**
     * Render this result as the JSON string consumed by the LLM. The shape
     * mirrors the field names exactly so a model trained on common
     * tool-result conventions can read it without a custom system prompt.
     */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("success", success);
        root.addProperty("message", message == null ? "" : message);
        if (timedOut) root.addProperty("timed_out", true);
        if (interrupted) root.addProperty("interrupted", true);
        if (data != null && !data.isEmpty()) {
            JsonObject dataObj = new JsonObject();
            for (Map.Entry<String, Object> e : data.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Number n) dataObj.addProperty(e.getKey(), n);
                else if (v instanceof Boolean b) dataObj.addProperty(e.getKey(), b);
                else if (v != null) dataObj.addProperty(e.getKey(), v.toString());
            }
            root.add("data", dataObj);
        }
        return root.toString();
    }
}
