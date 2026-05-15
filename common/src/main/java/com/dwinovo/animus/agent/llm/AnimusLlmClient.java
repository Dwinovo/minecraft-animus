package com.dwinovo.animus.agent.llm;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.agent.http.HttpLlmTransport;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.DeepSeekProvider;
import com.dwinovo.animus.agent.provider.LlmProvider;
import com.dwinovo.animus.agent.provider.OpenAIProvider;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.platform.services.IAnimusConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Mod-global LLM client. Wraps {@link HttpLlmTransport} (JDK
 * {@code java.net.http.HttpClient}, zero third-party deps) + a configured
 * {@link LlmProvider} (wire-format adapter). Lazy singleton — first
 * accessor triggers initialisation from {@link IAnimusConfig}.
 *
 * <h2>Why off-server</h2>
 * This client used to live in server-tick code; it now runs on the **client**
 * side so each player's API key drives their own conversation and their own
 * token spend. The server only executes tool calls (validated) and ships
 * results back. See {@code ClientAgentLoop} for the orchestration.
 *
 * <h2>Backend selection</h2>
 * Pick provider by {@code config.getProvider()}:
 * <ul>
 *   <li>{@code "deepseek"} — preserves {@code reasoning_content} for thinking models</li>
 *   <li>{@code "openai"} (default) — standard wire</li>
 * </ul>
 * URL = {@code config.getBaseUrl()} + {@code provider.chatCompletionsPath()}.
 * Empty base URL falls back to OpenAI's production host.
 */
public final class AnimusLlmClient {

    /** Used when config.getBaseUrl() is empty. */
    private static final String DEFAULT_OPENAI_BASE = "https://api.openai.com";

    private static volatile AnimusLlmClient instance;

    private final HttpLlmTransport transport = new HttpLlmTransport();
    private final LlmProvider provider;
    private final String fullUrl;
    private final String apiKey;
    private final String model;

    private AnimusLlmClient(IAnimusConfig config) {
        this.provider = pickProvider(config.getProvider());
        String base = config.getBaseUrl();
        if (base == null || base.isBlank()) base = DEFAULT_OPENAI_BASE;
        // Strip trailing slash to avoid double-slash in the joined URL.
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.fullUrl = base + provider.chatCompletionsPath();
        this.apiKey = config.getApiKey();
        String configured = config.getModel();
        this.model = (configured == null || configured.isBlank()) ? "gpt-5-2-mini" : configured;
        Constants.LOG.info("[animus-llm] client initialised: provider={}, model={}, url={}",
                provider.name(), model, fullUrl);
    }

    public static AnimusLlmClient instance() {
        AnimusLlmClient local = instance;
        if (local != null) return local;
        synchronized (AnimusLlmClient.class) {
            if (instance == null) {
                instance = new AnimusLlmClient(Services.CONFIG);
            }
            return instance;
        }
    }

    /** True iff config has a non-empty API key. */
    public static boolean isConfigured() {
        String key = Services.CONFIG.getApiKey();
        return key != null && !key.isBlank();
    }

    /**
     * Fire one chat completion. The system prompt from config is prepended
     * automatically — callers don't include it in {@code messages}.
     *
     * @return future of the parsed assistant turn; completes exceptionally
     *         on HTTP error ({@link com.dwinovo.animus.agent.http.LlmHttpException})
     *         or network failure (wrapped {@link java.io.IOException}).
     */
    public CompletableFuture<AssistantTurn> chat(List<ConvoState.Msg> messages,
                                                  Collection<AnimusTool> tools,
                                                  String systemPrompt) {
        // Translate stored messages to provider wire format.
        List<JsonObject> wire = new ArrayList<>(messages.size());
        for (ConvoState.Msg m : messages) {
            switch (m) {
                case ConvoState.Msg.User u -> wire.add(provider.buildUserMessage(u.content()));
                case ConvoState.Msg.Assistant a -> wire.add(provider.assistantToRequestMessage(a.turn()));
                case ConvoState.Msg.Tool t -> wire.add(provider.buildToolResultMessage(t.toolCallId(), t.content()));
            }
        }

        JsonArray toolList = provider.buildToolList(tools);
        JsonObject body = provider.buildRequestBody(model, systemPrompt, wire, toolList);

        return transport.post(fullUrl, apiKey, body)
                .thenApply(provider::parseResponseBody);
    }

    public LlmProvider provider() { return provider; }
    public String model() { return model; }

    private static LlmProvider pickProvider(String name) {
        if (name == null) return new OpenAIProvider();
        return switch (name.toLowerCase()) {
            case DeepSeekProvider.NAME -> new DeepSeekProvider();
            case OpenAIProvider.NAME -> new OpenAIProvider();
            default -> {
                Constants.LOG.warn("[animus-llm] unknown provider '{}', falling back to openai", name);
                yield new OpenAIProvider();
            }
        };
    }
}
