package com.dwinovo.animus.agent.provider;

/**
 * DashScope (阿里通义 / Qwen) provider. Aligned with LiteLLM's
 * <a href="https://github.com/BerriAI/litellm/blob/main/litellm/llms/dashscope/chat/transformation.py">{@code DashScopeChatConfig}</a>.
 *
 * <h2>Endpoint</h2>
 * Default {@code https://dashscope.aliyuncs.com/compatible-mode/v1} —
 * Alibaba's OpenAI-compatibility endpoint. The non-compat native DashScope
 * API has a completely different shape; we deliberately stick to the
 * {@code compatible-mode/v1} endpoint that mirrors OpenAI's chat
 * completions schema.
 *
 * <h2>Nothing else special at this layer</h2>
 * LiteLLM's config preserves {@code cache_control} flag on messages — we
 * don't expose that on our message shape, so the override is unnecessary.
 *
 * <p>Tool calling, streaming, and reasoning-field preservation all work
 * through the inherited OpenAI behaviour. Works with qwen-max, qwen-plus,
 * qwen-turbo, qwen3 family, and any other model exposed through DashScope's
 * compatibility endpoint.
 */
public final class DashScopeProvider extends OpenAIProvider {

    public static final String NAME = "dashscope";
    public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    @Override public String name() { return NAME; }

    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }
}
