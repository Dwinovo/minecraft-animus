package com.dwinovo.animus.agent.llm;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.platform.services.IAnimusConfig;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Mod-global async OpenAI client. Lazily constructed from the active
 * {@link IAnimusConfig} on first use; configuration changes require a
 * restart (acceptable for MVP — config writes are rare).
 *
 * <h2>Backend compatibility</h2>
 * Built on {@link OpenAIOkHttpClientAsync}; any backend that speaks
 * OpenAI's {@code POST /v1/chat/completions} JSON works by setting the
 * config {@code base_url}. Tested against OpenAI itself; DeepSeek, Anthropic
 * (via proxy), Ollama and vLLM all expose this same shape.
 *
 * <h2>Threading</h2>
 * All calls return {@link CompletableFuture}. The future's continuation
 * runs on the SDK's internal executor (cached thread pool); the agent loop
 * is expected to hop back to the server tick thread via
 * {@code server.execute(...)} before touching world state — see
 * {@link com.dwinovo.animus.agent.loop.AgentLoop AgentLoop} for that pattern.
 *
 * <h2>"Empty api key" guard</h2>
 * Refuses to even build the client if no key is configured (returns a failed
 * future). This keeps an unconfigured install from spamming the API with
 * 401s, and gives the agent layer a clean error path to surface in logs.
 */
public final class AnimusLlmClient {

    private static volatile AnimusLlmClient instance;

    private final OpenAIClientAsync client;
    private final String model;

    private AnimusLlmClient(IAnimusConfig config) {
        OpenAIOkHttpClientAsync.Builder b = OpenAIOkHttpClientAsync.builder()
                .apiKey(config.getApiKey());
        String baseUrl = config.getBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            b.baseUrl(baseUrl);
        }
        this.client = b.build();
        String configuredModel = config.getModel();
        this.model = (configuredModel == null || configuredModel.isBlank())
                ? "gpt-5-2-mini" : configuredModel;
        Constants.LOG.info("[animus-llm] client initialised: model={}, base_url={}",
                this.model, (baseUrl == null || baseUrl.isBlank()) ? "<default>" : baseUrl);
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

    /**
     * Fire a chat completion request. The system prompt (from config) is
     * prepended automatically — callers don't include it in {@code messages}.
     *
     * @return future of the completion; completes with an
     *         {@code OpenAIException} or {@code IOException} subclass on failure.
     */
    public CompletableFuture<ChatCompletion> chat(List<ConvoState.Msg> messages,
                                                  List<ChatCompletionTool> tools,
                                                  String systemPrompt) {
        ChatCompletionCreateParams.Builder b = ChatCompletionCreateParams.builder()
                .model(model);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            b.addSystemMessage(systemPrompt);
        }
        for (ConvoState.Msg m : messages) {
            switch (m) {
                case ConvoState.Msg.User u -> b.addUserMessage(u.content());
                case ConvoState.Msg.Assistant a -> b.addMessage(a.raw().toParam());
                case ConvoState.Msg.Tool t -> b.addMessage(
                        ChatCompletionToolMessageParam.builder()
                                .toolCallId(t.toolCallId())
                                .content(t.content())
                                .build());
            }
        }
        for (ChatCompletionTool tool : tools) {
            b.addTool(tool);
        }

        return client.chat().completions().create(b.build());
    }

    public String getModel() {
        return model;
    }

    /**
     * Returns true iff the configured api key is non-empty. The agent loop
     * checks this before attempting calls so the failure mode is one log line
     * per session, not one per turn.
     */
    public static boolean isConfigured() {
        String key = Services.CONFIG.getApiKey();
        return key != null && !key.isBlank();
    }
}
