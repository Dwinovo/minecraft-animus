package com.dwinovo.animus.agent.provider;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reference implementation of the OpenAI chat completions wire protocol.
 * Used directly for OpenAI itself and inherited from by lightly-extending
 * subclasses ({@link DeepSeekProvider}) that only need to override
 * response parsing / assistant-message reconstruction.
 *
 * <h2>Schema reference</h2>
 * Targets the {@code POST /v1/chat/completions} shape from OpenAI's API
 * docs as of GPT-4o / GPT-4.1 / GPT-5 family (the same shape DeepSeek,
 * Together, Groq, Mistral La Plateforme, Moonshot, etc. all conform to).
 *
 * <h2>Tool-call argument string vs object</h2>
 * The OpenAI spec ships tool_call.arguments as a JSON-string-typed string
 * (i.e. the JSON object encoded as a string). We preserve that on both
 * read ({@link AssistantTurn#toolCalls} field is also string) and write
 * (re-emit verbatim). Backends differ here and we trust the LLM's own
 * output verbatim.
 */
public class OpenAIProvider implements LlmProvider {

    public static final String NAME = "openai";
    /** Default path; subclasses can override if needed. */
    public static final String CHAT_PATH = "/v1/chat/completions";

    private static final Gson GSON = new Gson();

    @Override public String name() { return NAME; }
    @Override public String chatCompletionsPath() { return CHAT_PATH; }

    @Override
    public JsonObject buildUserMessage(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", content == null ? "" : content);
        return m;
    }

    @Override
    public JsonObject buildSystemMessage(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "system");
        m.addProperty("content", content == null ? "" : content);
        return m;
    }

    @Override
    public JsonObject buildToolResultMessage(String toolCallId, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "tool");
        m.addProperty("tool_call_id", toolCallId);
        m.addProperty("content", content == null ? "" : content);
        return m;
    }

    @Override
    public JsonObject assistantToRequestMessage(AssistantTurn turn) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "assistant");
        // Content can be null in OpenAI assistant turns when there are only
        // tool calls. We emit empty string instead — universally accepted.
        m.addProperty("content", turn.content());
        if (turn.hasToolCalls()) {
            JsonArray tcArr = new JsonArray();
            for (LlmToolCall tc : turn.toolCalls()) {
                tcArr.add(tc.toOpenAIJson());
            }
            m.add("tool_calls", tcArr);
        }
        // Re-inject any provider-specific extras (reasoning_content, ...).
        // The extras live at the message level (sibling to role/content)
        // so we copy each top-level entry onto m.
        for (var e : turn.extras().entrySet()) {
            m.add(e.getKey(), e.getValue());
        }
        return m;
    }

    @Override
    public JsonArray buildToolList(Collection<AnimusTool> tools) {
        JsonArray arr = new JsonArray();
        for (AnimusTool t : tools) {
            JsonObject fn = new JsonObject();
            fn.addProperty("name", t.name());
            fn.addProperty("description", t.description());
            fn.add("parameters", mapToJson(t.parameterSchema()));
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            wrapper.add("function", fn);
            arr.add(wrapper);
        }
        return arr;
    }

    @Override
    public JsonObject buildRequestBody(String model, String systemPrompt,
                                        List<JsonObject> messages, JsonArray tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray msgs = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.add(buildSystemMessage(systemPrompt));
        }
        for (JsonObject m : messages) msgs.add(m);
        body.add("messages", msgs);

        if (tools != null && !tools.isEmpty()) {
            body.add("tools", tools);
            body.addProperty("parallel_tool_calls", true);
        }
        return body;
    }

    @Override
    public AssistantTurn parseResponseBody(JsonObject body) {
        JsonObject msg = extractMessage(body);
        String content = stringOrEmpty(msg.get("content"));
        List<LlmToolCall> toolCalls = parseToolCalls(msg);
        JsonObject extras = extractExtras(msg);
        return new AssistantTurn(content, toolCalls, extras);
    }

    /** Pull {@code choices[0].message} out of the response body. */
    protected JsonObject extractMessage(JsonObject body) {
        if (!body.has("choices")) {
            throw new IllegalArgumentException("response has no 'choices' field: " + body);
        }
        JsonArray choices = body.getAsJsonArray("choices");
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("response 'choices' is empty: " + body);
        }
        JsonElement first = choices.get(0);
        if (!first.isJsonObject() || !first.getAsJsonObject().has("message")) {
            throw new IllegalArgumentException("response choices[0] has no 'message': " + body);
        }
        return first.getAsJsonObject().getAsJsonObject("message");
    }

    /** Pull and decode tool_calls from an assistant message. */
    protected List<LlmToolCall> parseToolCalls(JsonObject msg) {
        if (!msg.has("tool_calls") || !msg.get("tool_calls").isJsonArray()) {
            return List.of();
        }
        JsonArray arr = msg.getAsJsonArray("tool_calls");
        List<LlmToolCall> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (el.isJsonObject()) {
                out.add(LlmToolCall.fromOpenAIJson(el.getAsJsonObject()));
            }
        }
        return out;
    }

    /**
     * Identify non-standard fields on the assistant message that must
     * round-trip in subsequent requests. The base impl returns an empty
     * extras object — subclasses ({@link DeepSeekProvider}) override to
     * harvest backend-specific echo-required fields.
     */
    protected JsonObject extractExtras(JsonObject msg) {
        return new JsonObject();
    }

    private static String stringOrEmpty(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }

    private static JsonElement mapToJson(Object value) {
        return GSON.toJsonTree(value);
    }
}
