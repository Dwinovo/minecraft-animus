package com.dwinovo.animus.agent.provider;

import com.google.gson.JsonObject;

/**
 * Moonshot AI (Kimi) provider. Strictly aligned with LiteLLM's
 * <a href="https://github.com/BerriAI/litellm/blob/main/litellm/llms/moonshot/chat/transformation.py">{@code MoonshotChatConfig}</a>.
 *
 * <h2>What LiteLLM does for Moonshot (and so do we)</h2>
 * <ol>
 *   <li><b>Endpoint:</b> defaults to {@code https://api.moonshot.ai/v1} —
 *       LiteLLM's {@code _get_openai_compatible_provider_info} returns
 *       exactly this string.</li>
 *   <li><b>{@code fill_reasoning_content} safety net:</b> Moonshot reasoning
 *       models (kimi-thinking-preview and kimi-k2.5 family) reject any
 *       assistant message that has {@code tool_calls} but no
 *       {@code reasoning_content} field. LiteLLM's helper injects a single
 *       space when the field is missing — "the minimum value the API
 *       accepts". We mirror that fallback in
 *       {@link #assistantToRequestMessage}.</li>
 * </ol>
 *
 * <h2>What LiteLLM does that we don't</h2>
 * <ul>
 *   <li>Content list → string conversion: skipped because our agent layer
 *       always emits content as a plain string.</li>
 *   <li>{@code functions} parameter exclusion: we don't expose that
 *       deprecated parameter at all (use {@code tools}).</li>
 *   <li>kimi-thinking-preview tool-call disable: that model can't use tools,
 *       so deploying with it would silently no-op our move_to. Phase-2:
 *       warn on init if the configured model is kimi-thinking-preview AND
 *       any tools are registered.</li>
 * </ul>
 *
 * <p>Non-standard response fields (notably {@code reasoning_content} from
 * reasoning models) are preserved automatically by the framework-level
 * extras-capture in {@link OpenAIProvider}; no Moonshot-specific override
 * needed for the round-trip.
 */
public final class MoonshotProvider extends OpenAIProvider {

    public static final String NAME = "moonshot";
    public static final String DEFAULT_BASE_URL = "https://api.moonshot.ai/v1";

    @Override public String name() { return NAME; }

    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }

    /**
     * LiteLLM {@code fill_reasoning_content} backstop: Moonshot reasoning
     * models require {@code reasoning_content} on every assistant message
     * that contains {@code tool_calls}. The parent's
     * {@code assistantToRequestMessage} already echoes any captured
     * extras; this override fires only when the field is missing
     * (e.g. previous chunk stream didn't carry it).
     */
    @Override
    public JsonObject assistantToRequestMessage(AssistantTurn turn) {
        JsonObject m = super.assistantToRequestMessage(turn);
        if (m.has("tool_calls") && !m.has("reasoning_content")) {
            m.addProperty("reasoning_content", " ");
        }
        return m;
    }
}
