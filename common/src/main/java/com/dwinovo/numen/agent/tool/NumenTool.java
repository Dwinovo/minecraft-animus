package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * The LLM-facing surface of a single tool, plus its two execution entry points.
 * A tool is just: what the model sees ({@link #name} / {@link #description} /
 * {@link #parameterSchema}) and how it runs. There is deliberately <b>no
 * category</b> on this contract — no query/local/world flags. How a tool runs
 * (client-side, a server read, a sliced job, a body task) is the
 * implementation's private business, inferred from its own shape.
 *
 * <h2>Execution</h2>
 * <ul>
 *   <li>{@link #invoke(ToolCall)} — client dispatch. The agent loop hands over a
 *       {@link ToolCall} and is notified of the result through it; the tool
 *       either completes immediately or ships itself to the server body.</li>
 *   <li>{@link #runOnServer} — server dispatch. Run against the live companion;
 *       a read replies immediately, a sliced job replies later, a body action
 *       enqueues a task whose result returns via the task lifecycle.</li>
 *   <li>{@link #checkArgs} — validate the model's arguments offline (no
 *       execution), used by the benchmark and any pre-flight check.</li>
 * </ul>
 *
 * <p>In practice every tool is authored as an {@code @NumenAction} method and
 * realised by {@link NumenActionTool}; this interface is the runtime contract
 * the registry, agent loop and network layer speak.
 */
public interface NumenTool {

    /** Tool name as the LLM sees it. {@code snake_case}. */
    String name();

    /**
     * Description shown to the LLM — the single biggest lever on whether the model
     * picks this tool correctly. Cover what it does, WHEN to use it (and when not),
     * what each non-obvious parameter means, and any caveat.
     */
    String description();

    /** JSON Schema (OpenAI tool-parameter dialect) for the tool's arguments. */
    Map<String, Object> parameterSchema();

    /** Deadline in game ticks for a body task (20 ticks = 1 s); ignored by instant tools. */
    long defaultTimeoutTicks();

    /**
     * Client-side dispatch. The tool runs the call (and {@link ToolCall#complete
     * completes} it) or {@link ToolCall#shipToServer ships} it to the server body.
     * The agent loop calls only this and is blind to which path is taken.
     */
    void invoke(ToolCall call);

    /**
     * Server-side dispatch against the live companion (tick thread, owner-verified).
     * A read or sliced job delivers its result through {@code reply}; a body action
     * enqueues a task and the result returns later via {@code TaskResultPayload}
     * (so {@code reply} goes unused for those). Throws {@link IllegalArgumentException}
     * for malformed arguments; the caller reports it as a failed result.
     */
    void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply);

    /**
     * Validate that {@code args} coerce to this tool's parameters, without executing.
     * Throws {@link IllegalArgumentException} if an argument is missing or ill-typed.
     */
    void checkArgs(JsonObject args);
}
