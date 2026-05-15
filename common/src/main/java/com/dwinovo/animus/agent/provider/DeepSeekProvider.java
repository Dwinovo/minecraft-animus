package com.dwinovo.animus.agent.provider;

/**
 * DeepSeek-flavour OpenAI provider. Strictly aligned with LiteLLM's
 * <a href="https://github.com/BerriAI/litellm/blob/main/litellm/llms/deepseek/chat/transformation.py">{@code DeepSeekChatConfig}</a>
 * reference implementation.
 *
 * <h2>What LiteLLM does for DeepSeek (and so do we)</h2>
 * <ol>
 *   <li><b>Endpoint:</b> defaults to {@code /beta/chat/completions} —
 *       LiteLLM's {@code _get_openai_compatible_provider_info} returns
 *       {@code https://api.deepseek.com/beta} as the base URL. The
 *       {@code /beta} prefix unlocks DeepSeek's prefix-completion family
 *       of features alongside standard chat completions.</li>
 *   <li><b>Inherits all message handling:</b> LiteLLM's class extends
 *       {@code OpenAIGPTConfig} and overrides nothing about response
 *       parsing or message reconstruction. Non-standard response fields
 *       (notably {@code reasoning_content} from V4 thinking mode) are
 *       preserved by the framework-level Pydantic mechanism, not by
 *       DeepSeek-specific code. Our equivalent is the
 *       {@link OpenAIProvider#extractExtras} / {@link OpenAIProvider#captureChunkExtras}
 *       default behaviour, which captures every unknown top-level field.</li>
 * </ol>
 *
 * <h2>What LiteLLM does NOT do for DeepSeek (and neither do we now)</h2>
 * <ul>
 *   <li>No {@code fill_reasoning_content} safety net like the Moonshot
 *       provider has. LiteLLM bets the framework-level preservation is
 *       enough; we follow that bet.</li>
 *   <li>No content-list to string conversion is needed in our code path
 *       (we always emit content as a plain string from the agent layer).</li>
 * </ul>
 *
 * <h2>Optional thinking-mode parameter</h2>
 * LiteLLM also maps user-supplied {@code thinking} / {@code reasoning_effort}
 * options to DeepSeek's {@code thinking: {type: "enabled"}} request body
 * field. We don't expose a config knob for this yet — V4 models default
 * to thinking mode anyway, so the field is redundant for the common case.
 * Add a config field + {@code buildRequestBody} override when a user needs
 * to force a specific mode.
 */
public final class DeepSeekProvider extends OpenAIProvider {

    public static final String NAME = "deepseek";

    /** LiteLLM default: {@code https://api.deepseek.com/beta/chat/completions}. */
    private static final String CHAT_PATH = "/beta/chat/completions";

    @Override public String name() { return NAME; }

    @Override public String chatCompletionsPath() { return CHAT_PATH; }
}
