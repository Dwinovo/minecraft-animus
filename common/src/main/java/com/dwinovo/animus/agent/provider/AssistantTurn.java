package com.dwinovo.animus.agent.provider;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * One assistant-turn response from an LLM, decomposed into the standard
 * fields plus a provider-specific extras bag.
 *
 * <h2>Why an extras map</h2>
 * Backends extend OpenAI's spec with proprietary fields that **must be
 * echoed back** on the next request (DeepSeek's {@code reasoning_content},
 * potentially others as model families evolve). Stripping these on
 * serialisation breaks multi-turn chats. The extras bag captures whatever
 * non-standard top-level keys the parser found, and the provider's
 * {@code assistantToRequestMessage} re-injects them when constructing the
 * next request.
 *
 * <p>This is the mechanism that fixes the {@code 400 The reasoning_content
 * in the thinking mode must be passed back to the API} case.
 *
 * @param content      assistant message body text (may be empty when the
 *                     turn is pure tool calls)
 * @param toolCalls    tool calls in this turn (empty list = no tool use, the
 *                     model finalised with a text reply)
 * @param extras       provider-specific non-standard fields to round-trip
 *                     back. Lives at the message level (sibling to {@code role},
 *                     {@code content}, {@code tool_calls} in OpenAI's schema).
 */
public record AssistantTurn(String content,
                            List<LlmToolCall> toolCalls,
                            JsonObject extras) {

    public AssistantTurn {
        if (content == null) content = "";
        if (toolCalls == null) toolCalls = List.of();
        if (extras == null) extras = new JsonObject();
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
