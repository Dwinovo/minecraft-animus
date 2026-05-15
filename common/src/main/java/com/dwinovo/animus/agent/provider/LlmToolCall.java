package com.dwinovo.animus.agent.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Single tool invocation extracted from an LLM response. Carries enough to
 * round-trip back into the next request (the {@code id} must be echoed
 * verbatim in the {@code tool_call_id} of the role:tool reply, or backends
 * 400).
 *
 * <p>{@code arguments} stays a string here (raw JSON as the LLM emitted it,
 * before our parsing). This avoids a re-serialise-on-replay round-trip and
 * matches OpenAI's actual wire field which is also a string.
 *
 * @param id           identifier the LLM minted for this tool_call; must be
 *                     echoed in the matching tool result
 * @param name         tool name (matches a registered {@code AnimusTool.name()})
 * @param arguments    raw JSON arguments string from the LLM
 */
public record LlmToolCall(String id, String name, String arguments) {

    /** Build the OpenAI-shape JSON for this tool call (used inside the assistant message). */
    public JsonObject toOpenAIJson() {
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("arguments", arguments);
        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        root.addProperty("type", "function");
        root.add("function", fn);
        return root;
    }

    /** Parse an OpenAI-shape tool_call object back into this DTO. */
    public static LlmToolCall fromOpenAIJson(JsonObject obj) {
        String id = stringOrEmpty(obj.get("id"));
        JsonObject fn = obj.has("function") && obj.get("function").isJsonObject()
                ? obj.getAsJsonObject("function") : new JsonObject();
        String name = stringOrEmpty(fn.get("name"));
        String args = stringOrEmpty(fn.get("arguments"));
        return new LlmToolCall(id, name, args);
    }

    private static String stringOrEmpty(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }
}
