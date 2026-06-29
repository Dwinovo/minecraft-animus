package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.task.TaskRecord;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * LLM-facing surface of a single action the entity can take. Sits one layer
 * above {@link TaskRecord}: a tool declares its parameter schema for the
 * model, validates JSON args coming back from the API, and translates them
 * into a typed task record that the {@link com.dwinovo.numen.task.CompanionTask task layer} executes.
 *
 * <h2>Three tool categories</h2>
 * <ul>
 *   <li><b>World-action tools</b> (default) — move_to, auto_mine, …: ship to
 *       the server as {@code ExecuteToolPayload}, run through the
 *       {@code TaskQueue}/{@code CompanionTickDispatcher} machinery (they occupy the body),
 *       result returns via {@code TaskResultPayload}.</li>
 *   <li><b>Query tools</b> ({@link #isQuery()}) — the perception family:
 *       read-only, server-side, executed synchronously by the payload handler
 *       WITHOUT queueing (they must not block behind a running move_to), reply
 *       immediately. Server-side because the owner's client stops seeing the
 *       entity beyond tracking range — a working companion may be thousands of
 *       blocks away in chunk-ticket-loaded terrain the client never has.</li>
 *   <li><b>Local tools</b> ({@link #isLocal()}) — todowrite / load_skill: pure
 *       agent-side bookkeeping with no world contact at all; the conversation
 *       and the skill registry live with the brain on the client, so these
 *       never cross the network.</li>
 * </ul>
 *
 * <h2>Why separate from Task</h2>
 * Two reasons:
 * <ol>
 *   <li>One tool can map to multiple task records (a future {@code patrol}
 *       tool would emit a sequence of moveTo records). Hard-binding tool to
 *       a single task class makes that impossible.</li>
 *   <li>Reverse: one task class can back several tools with different schemas
 *       — e.g. {@code move_to(x,y,z)} and {@code follow_player(name)} could
 *       both emit {@code MoveToTaskRecord} but expose different ergonomics
 *       to the LLM.</li>
 * </ol>
 * The cost is one extra translation hop. Worth it.
 *
 * <h2>Schema shape</h2>
 * {@link #parameterSchema()} returns a plain {@code Map<String,Object>}
 * conforming to OpenAI's tool-parameter JSON Schema dialect. Top-level keys
 * are typically {@code type}, {@code properties}, {@code required},
 * {@code additionalProperties}. The {@link ToolAdapter} wraps the map into
 * the SDK's {@code FunctionParameters} type without further validation.
 *
 * <h2>Timeout policy</h2>
 * Each tool declares its own default timeout in game ticks. The agent loop
 * uses this to compute the {@link TaskRecord#getDeadlineGameTime() deadline}
 * at enqueue time. There is intentionally no per-call override — the LLM is
 * not trusted to set timeouts, and overrideable timeouts complicate the API
 * surface for no MVP benefit.
 *
 * <h2>Local vs world-action tools</h2>
 * Two distinct categories of tool now coexist:
 * <ul>
 *   <li><b>World-action tools</b> ({@code isLocal() == false}, default) — like
 *       {@code move_to}: dispatch to the server via {@code ExecuteToolPayload},
 *       run as a {@code TaskRecord}/{@code CompanionTask} on the server tick
 *       thread, result comes back via {@code TaskResultPayload}. The
 *       {@link #toTaskRecord} method is the one the server invokes.</li>
 *   <li><b>Local (client-side) tools</b> ({@code isLocal() == true}) — like
 *       {@code todowrite} / {@code load_skill}: pure agent-side bookkeeping
 *       with no world side-effect. {@link EntityAgentLoop} executes them
 *       synchronously via {@link #executeLocal(JsonObject)}, writes the
 *       result straight into the conversation as a {@code role:tool} message,
 *       and never goes to the server. {@link #toTaskRecord} is never called
 *       for these — it should throw if called.</li>
 * </ul>
 */
public interface NumenTool {

    /** Tool name as the LLM sees it. {@code snake_case}. Must match the Task's tool name field. */
    String name();

    /**
     * Description shown to the LLM — the single biggest lever on whether the model
     * picks this tool correctly, so make it thorough (Anthropic's guidance: aim for
     * several sentences, more for a complex tool). Cover what it does, WHEN to use it
     * (and when not — how it differs from neighbouring tools), what each non-obvious
     * parameter means, and any caveat. The per-tool how-to lives HERE, not in the
     * system prompt, so it can't rot: the live schema rides on every request. It
     * counts as prompt tokens, but selection accuracy is worth far more than the
     * tokens saved by terseness.
     */
    String description();

    /** JSON Schema for {@link #toTaskRecord} arguments. See class-level Javadoc. */
    Map<String, Object> parameterSchema();

    /**
     * Execute this tool for one call — the <em>only</em> execution entry point
     * the agent loop calls. The loop is blind to <em>how</em> a tool runs: it
     * hands over a {@link ToolCall} and waits to be notified of the result
     * through it. Whether the work happens on the client thread, on the server
     * body, or out on some external service is entirely the tool's business.
     *
     * <p>The default implementation is the only place the built-in local-vs-
     * server routing now lives:
     * <ul>
     *   <li>{@link #isLocal()} tools run {@link #executeLocal} right here on the
     *       agent (client) thread and {@link ToolCall#complete complete}
     *       immediately;</li>
     *   <li>everything else is {@link ToolCall#shipToServer shipped to the
     *       server body}, where {@code ExecuteToolPayload} picks the
     *       query / async-query / world-action scheduling discipline and the
     *       result returns asynchronously.</li>
     * </ul>
     * The category flags ({@link #isLocal} / {@link #isQuery} /
     * {@link #isAsyncQuery}) are now consulted only here and on the server — the
     * loop never inspects them. A tool that owns its execution end-to-end (e.g.
     * an MCP bridge proxying to an external server) can override {@code invoke}
     * directly and call {@link ToolCall#complete} on whatever thread it likes.
     */
    default void invoke(ToolCall call) {
        if (isLocal()) {
            String resultJson;
            try {
                resultJson = executeLocal(call.args(), call.ctx());
            } catch (RuntimeException ex) {
                resultJson = com.dwinovo.numen.task.TaskResult.fail(ex.getMessage()).toJson();
            }
            call.complete(resultJson);
        } else {
            call.shipToServer();
        }
    }

    /** Deadline in game ticks. 20 ticks = 1 second of real time (vanilla rate). */
    long defaultTimeoutTicks();

    /**
     * Translate validated LLM arguments into an actionable task record.
     * Only called for world-action tools ({@link #isLocal()} {@code == false}).
     *
     * @param toolCallId        the {@code id} from the LLM's tool_call; must be
     *                          carried through to the matching tool result message
     * @param args              parsed JSON arguments (already validated as JSON;
     *                          schema conformance is the model's job in {@code strict} mode)
     * @param currentGameTime   {@code level.getGameTime()} at submit time;
     *                          tools compute {@code deadline = now + timeout}
     * @return the task record to enqueue
     * @throws IllegalArgumentException for missing / malformed args; the agent
     *                                  loop catches this and reports it back to
     *                                  the LLM as a failed tool result so the
     *                                  conversation continues
     */
    default TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        throw new UnsupportedOperationException(
                "toTaskRecord called on local tool " + name() + " — use executeLocal instead");
    }

    /**
     * Local tools = pure client-side bookkeeping (todowrite, load_skill).
     * World-action tools (move_to, future place_block, ...) leave this false
     * and ship over the network as {@code ExecuteToolPayload}.
     *
     * <p>Default {@code false} preserves backward-compatibility with every
     * existing tool.
     */
    default boolean isLocal() {
        return false;
    }

    /**
     * Query tools = read-only perception (get_self_status, inspect_block, …).
     * They ship over the network like world-action tools, but the server
     * executes {@link #executeQuery} immediately on the tick thread and
     * replies — no {@code TaskRecord}, no queue, no body occupancy.
     */
    default boolean isQuery() {
        return false;
    }

    /**
     * Async query tools = read-only perception too expensive for one tick
     * (scan_blocks at long range). Same contract as {@link #isQuery} — no
     * queue, no body occupancy — but {@link #startAsyncQuery} registers a
     * budget-sliced server job and the reply arrives on a later tick through
     * the given consumer (exactly once).
     */
    default boolean isAsyncQuery() {
        return false;
    }

    /**
     * Kick off the sliced job for an async query. Called on the server tick
     * thread with a resolved, owner-verified entity. {@code reply} accepts
     * the raw JSON tool-result string and may be invoked on any later tick.
     *
     * @throws IllegalArgumentException for malformed args; the payload
     *                                  handler reports it as a failed result
     */
    default void startAsyncQuery(JsonObject args, com.dwinovo.numen.entity.NumenPlayer entity,
                                 java.util.function.Consumer<String> reply) {
        throw new UnsupportedOperationException("not an async query tool: " + name());
    }

    /**
     * Execute a read-only query against the live server-side entity/world.
     * Only called when {@link #isQuery()} is true, on the server tick thread,
     * with a resolved non-null entity. The returned string becomes the
     * {@code role:tool} message content verbatim.
     *
     * <p><b>Return the shared envelope.</b> Build the result with
     * {@link com.dwinovo.numen.task.TaskResult} and call {@code toJson()}, the
     * same {@code {success, message, data}} shape every world-action tool
     * returns — so the model reads one contract and the agent loop can
     * post-process results uniformly (e.g. work-block harvesting). Put the
     * human-readable observation (including any ASCII layout) in {@code message};
     * Gson escapes it safely. Use {@code TaskResult.fail} for a degenerate read
     * (no target, nothing open) so the model sees {@code success:false}.
     *
     * @throws IllegalArgumentException for malformed args; the payload handler
     *                                  converts it into a failed tool result
     */
    default String executeQuery(JsonObject args, com.dwinovo.numen.entity.NumenPlayer entity) {
        throw new UnsupportedOperationException(
                "executeQuery called on non-query tool " + name());
    }

    /**
     * Execute a local tool synchronously on the client agent loop thread.
     * Only called when {@link #isLocal()} returns true. The returned string
     * is written straight into the conversation as the {@code role:tool}
     * message content — typically a JSON or XML-wrapped payload the LLM
     * will read in the next turn.
     *
     * <p>Two overloads exist:
     * <ul>
     *   <li>{@link #executeLocal(JsonObject, ClientToolContext)} — preferred,
     *       gives the tool access to the live entity / world. Perception
     *       tools override this.</li>
     *   <li>{@link #executeLocal(JsonObject)} — context-less, kept for
     *       tools that are pure agent-side bookkeeping
     *       (todowrite / load_skill).</li>
     * </ul>
     * Default forwarding from the context-aware overload to the bare one
     * means old tools don't have to change.
     *
     * @param args parsed JSON arguments from the LLM's tool_call
     * @param ctx  live entity/world handle, see {@link ClientToolContext}
     * @return content of the role:tool reply
     * @throws IllegalArgumentException for missing / malformed args; the agent
     *                                  loop catches this and reports the failure
     *                                  back to the LLM so the conversation continues
     */
    default String executeLocal(JsonObject args, ClientToolContext ctx) {
        return executeLocal(args);
    }

    /** Context-less overload — see {@link #executeLocal(JsonObject, ClientToolContext)}. */
    default String executeLocal(JsonObject args) {
        throw new UnsupportedOperationException(
                "executeLocal called on non-local tool " + name() + " — use toTaskRecord instead");
    }
}
