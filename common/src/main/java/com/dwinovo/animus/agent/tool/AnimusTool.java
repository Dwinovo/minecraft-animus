package com.dwinovo.animus.agent.tool;

import com.dwinovo.animus.task.TaskRecord;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * LLM-facing surface of a single action the entity can take. Sits one layer
 * above {@link TaskRecord}: a tool declares its parameter schema for the
 * model, validates JSON args coming back from the API, and translates them
 * into a typed task record that the {@link com.dwinovo.animus.task.LlmTaskGoal goal layer} executes.
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
 */
public interface AnimusTool {

    /** Tool name as the LLM sees it. {@code snake_case}. Must match the Task's tool name field. */
    String name();

    /** Short description shown to the LLM. Keep under ~120 chars; it counts as prompt tokens. */
    String description();

    /** JSON Schema for {@link #toTaskRecord} arguments. See class-level Javadoc. */
    Map<String, Object> parameterSchema();

    /** Deadline in game ticks. 20 ticks = 1 second of real time (vanilla rate). */
    long defaultTimeoutTicks();

    /**
     * Translate validated LLM arguments into an actionable task record.
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
    TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime);
}
