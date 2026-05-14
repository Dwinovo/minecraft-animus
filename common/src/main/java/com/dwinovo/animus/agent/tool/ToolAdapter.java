package com.dwinovo.animus.agent.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionTool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Single point where our internal {@link AnimusTool} representation meets the
 * OpenAI SDK's wire-format types. Keeping this as the only file that imports
 * {@code com.openai.models.chat.completions.*} for tool-shape concerns means
 * the rest of the codebase doesn't bend to OpenAI's specific field names —
 * if the SDK's API surface shifts on the next 4.x bump, or if we add a
 * second backend (Anthropic, local llama.cpp via OpenAI-compatible proxy),
 * the patch lands here.
 *
 * <p>TouhouLittleMaid's tool layer made the opposite choice (OpenAI field
 * names leak into their public {@code ITool} interface), and is now stuck
 * with Anthropic-compatibility bug reports (issue #1104). Worth the extra
 * adapter class to avoid that.
 */
public final class ToolAdapter {

    private ToolAdapter() {}

    /**
     * Build the SDK's {@link ChatCompletionTool} list from our internal tools.
     * Called once per {@code chat()} request — the result list is short
     * (handful of tools) and constructing it is cheap, no need to cache.
     */
    public static List<ChatCompletionTool> toOpenAITools(Collection<AnimusTool> tools) {
        List<ChatCompletionTool> out = new ArrayList<>(tools.size());
        for (AnimusTool t : tools) {
            FunctionParameters params = toFunctionParameters(t.parameterSchema());
            ChatCompletionFunctionTool fnTool = ChatCompletionFunctionTool.builder()
                    .function(FunctionDefinition.builder()
                            .name(t.name())
                            .description(t.description())
                            .parameters(params)
                            // strict mode forces the model to emit exactly the
                            // declared schema — required for predictable parsing
                            // and supported by GPT-4o family + Claude via proxy.
                            .strict(true)
                            .build())
                    .build();
            out.add(ChatCompletionTool.ofFunction(fnTool));
        }
        return out;
    }

    /**
     * Wrap a plain Java map (as returned by {@link AnimusTool#parameterSchema()})
     * into the SDK's {@code FunctionParameters} type by treating each top-level
     * entry as an additional property. The map values can be arbitrary
     * Jackson-compatible Java values; {@link JsonValue#from(Object)} handles
     * the conversion.
     */
    public static FunctionParameters toFunctionParameters(Map<String, Object> schema) {
        FunctionParameters.Builder b = FunctionParameters.builder();
        for (Map.Entry<String, Object> e : schema.entrySet()) {
            b.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
        }
        return b.build();
    }

    /**
     * Parse the {@code arguments} string an LLM ships in a tool_call. The
     * SDK delivers it as a raw JSON-string field even under {@code strict}
     * mode, so we do the parse ourselves via Gson (already on MC's classpath,
     * so no extra dependency).
     *
     * @throws IllegalArgumentException if the string isn't valid JSON or
     *         doesn't decode to a JSON object
     */
    public static JsonObject parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new JsonObject();
        }
        try {
            var parsed = JsonParser.parseString(argumentsJson);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException(
                        "tool arguments must be a JSON object, got " + parsed);
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("malformed tool arguments JSON: " + ex.getMessage(), ex);
        }
    }
}
