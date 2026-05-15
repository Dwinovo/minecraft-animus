package com.dwinovo.animus.agent.provider;

/**
 * Volcengine / Doubao (豆包) provider. Aligned with LiteLLM's
 * <a href="https://github.com/BerriAI/litellm/blob/main/litellm/llms/volcengine/chat/transformation.py">{@code VolcEngineChatConfig}</a>
 * (note: the default URL constant in LiteLLM lives in
 * {@code get_llm_provider_logic.py} rather than the transformation file —
 * the transformation class just adds {@code thinking} param handling).
 *
 * <h2>Endpoint</h2>
 * Default {@code https://ark.cn-beijing.volces.com/api/v3} — the Volcengine
 * Ark service that fronts Doubao (字节跳动豆包) models. The {@code ark} in
 * the hostname is the service name; {@code /api/v3} is the version prefix.
 *
 * <h2>Nothing else special at this layer</h2>
 * LiteLLM's config adds {@code thinking} parameter translation (supports
 * {@code type: "enabled" | "disabled" | "auto"} with model-specific
 * defaults). We don't expose that knob yet — Volcengine Doubao thinking-
 * capable models pick a sensible default on their own. Add a config flag
 * and override {@code buildRequestBody} when a user needs fine control.
 *
 * <p>Tool calling, streaming, and reasoning-field preservation all work
 * through the inherited OpenAI behaviour.
 */
public final class VolcengineProvider extends OpenAIProvider {

    public static final String NAME = "volcengine";
    public static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";

    @Override public String name() { return NAME; }

    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }
}
