package com.dwinovo.animus.agent.provider;

import com.google.gson.JsonObject;

/**
 * DeepSeek-flavour OpenAI provider. Identical wire shape on request, but
 * thinking models (deepseek-v3+ family, deepseek-reasoner) emit a
 * {@code reasoning_content} field on assistant responses that **must be
 * echoed back verbatim** on the next request, or DeepSeek returns
 * {@code 400 The reasoning_content in the thinking mode must be passed
 * back to the API}.
 *
 * <p>The base OpenAI SDK / generic OpenAIProvider strips this field on
 * round-trip because it's not in the standard OpenAI schema. This
 * subclass intercepts the parse step, copies {@code reasoning_content}
 * (and any other non-standard top-level fields) into the {@link AssistantTurn#extras}
 * bag, and the inherited {@link #assistantToRequestMessage} re-injects
 * them on the next request — round-trip preserved.
 *
 * <h2>What "non-standard" means here</h2>
 * Standard OpenAI assistant message fields are
 * {@code role / content / tool_calls / refusal}. Anything else — including
 * {@code reasoning_content} — gets captured as an extra. Empty objects /
 * empty strings get captured too (they may be required by the backend even
 * when empty; safer to round-trip).
 */
public final class DeepSeekProvider extends OpenAIProvider {

    public static final String NAME = "deepseek";

    /** Fields that are part of the standard OpenAI assistant message and don't need extras-capture. */
    private static final java.util.Set<String> STANDARD_FIELDS = java.util.Set.of(
            "role", "content", "tool_calls", "refusal", "audio");

    @Override public String name() { return NAME; }

    @Override
    protected JsonObject extractExtras(JsonObject msg) {
        JsonObject extras = new JsonObject();
        for (var e : msg.entrySet()) {
            if (!STANDARD_FIELDS.contains(e.getKey())) {
                extras.add(e.getKey(), e.getValue());
            }
        }
        return extras;
    }
}
