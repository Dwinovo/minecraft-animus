package com.dwinovo.animus.agent.provider;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.List;

/**
 * Wire-format adapter for one LLM backend family.
 *
 * <h2>Why this interface exists</h2>
 * Most "OpenAI-compatible" backends are 95% compatible and 5% proprietary
 * extensions — DeepSeek adds {@code reasoning_content}, Azure renames
 * a few fields, Anthropic via OpenAI-compat proxy doesn't quite return the
 * same shape. Centralising the wire-format translation here keeps the rest
 * of the agent layer (ConvoState, AgentLoop, ToolAdapter) provider-agnostic
 * — they speak our internal types ({@link AssistantTurn}, {@link LlmToolCall})
 * and the provider does the JSON in/out.
 *
 * <p>For genuinely non-OpenAI protocols (Anthropic Messages API, Gemini)
 * you'd implement this interface from scratch with a different request /
 * response shape. The interface deliberately avoids assuming OpenAI fields
 * leak through to the caller.
 *
 * <h2>What providers DO</h2>
 * Build wire messages, build request bodies, parse response bodies, and
 * handle the round-trip of non-standard fields ({@link AssistantTurn#extras}).
 *
 * <h2>What providers DON'T do</h2>
 * No HTTP I/O (that's {@link com.dwinovo.animus.agent.http.HttpLlmTransport}),
 * no convo state (that's {@code ConvoState}), no LLM-side loop control
 * (that's {@code ClientAgentLoop}).
 */
public interface LlmProvider {

    /** Stable id used in config (e.g. {@code "openai"}, {@code "deepseek"}). */
    String name();

    /** URL path appended to the configured {@code baseUrl}, e.g. {@code "/v1/chat/completions"}. */
    String chatCompletionsPath();

    /** Build the user-role wire message for {@code content}. */
    JsonObject buildUserMessage(String content);

    /** Build the system-role wire message for {@code content}. */
    JsonObject buildSystemMessage(String content);

    /** Build the tool-role wire message echoing {@code toolCallId}'s result. */
    JsonObject buildToolResultMessage(String toolCallId, String content);

    /**
     * Convert a stored {@link AssistantTurn} back into the wire-format
     * assistant message for the next request. Must re-inject {@code extras}
     * so backends with proprietary required-echo fields stay happy.
     */
    JsonObject assistantToRequestMessage(AssistantTurn turn);

    /** Build the {@code tools} array from our internal {@link AnimusTool} registry. */
    JsonArray buildToolList(Collection<AnimusTool> tools);

    /**
     * Assemble the full request body for one chat completion.
     *
     * @param model         model id string ({@code "deepseek-chat"}, {@code "gpt-4o"}, ...)
     * @param systemPrompt  may be empty — provider should skip if so
     * @param messages      already-built wire-format message objects in order
     * @param tools         already-built wire-format tools array (may be empty)
     */
    JsonObject buildRequestBody(String model, String systemPrompt,
                                List<JsonObject> messages, JsonArray tools);

    /** Decode the response body into our internal {@link AssistantTurn}. */
    AssistantTurn parseResponseBody(JsonObject body);
}
