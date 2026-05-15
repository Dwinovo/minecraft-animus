package com.dwinovo.animus.agent.provider;

/**
 * MiniMax provider. Aligned with LiteLLM's
 * <a href="https://github.com/BerriAI/litellm/blob/main/litellm/llms/minimax/chat/transformation.py">{@code MinimaxChatConfig}</a>.
 *
 * <h2>Endpoint</h2>
 * Default {@code https://api.minimax.io/v1} (international). LiteLLM also
 * notes a China-only host {@code https://api.minimaxi.com/v1} — users can
 * override via {@code config.baseUrl} if they're behind a region split.
 *
 * <h2>Nothing else special</h2>
 * MiniMax is essentially a clean OpenAI-compatible backend. LiteLLM's
 * config does these things we don't need:
 * <ul>
 *   <li>Preserves {@code cache_control} flag on messages — we don't expose
 *       that on our message shape.</li>
 *   <li>Supports {@code thinking} / {@code reasoning_split} params for
 *       reasoning-capable models — we don't expose these knobs yet.</li>
 * </ul>
 * Both can be added later by overriding {@code buildRequestBody} if needed.
 *
 * <p>Tool calling, streaming, and non-standard response field preservation
 * (reasoning extras) all work via the inherited OpenAI behaviour. Just
 * a base URL override.
 */
public final class MinimaxProvider extends OpenAIProvider {

    public static final String NAME = "minimax";
    public static final String DEFAULT_BASE_URL = "https://api.minimax.io/v1";

    @Override public String name() { return NAME; }

    @Override public String defaultBaseUrl() { return DEFAULT_BASE_URL; }
}
