package com.dwinovo.animus.agent.llm;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.agent.http.HttpLlmTransport;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.DashScopeProvider;
import com.dwinovo.animus.agent.provider.DeepSeekProvider;
import com.dwinovo.animus.agent.provider.LlmProvider;
import com.dwinovo.animus.agent.provider.LlmToolCall;
import com.dwinovo.animus.agent.provider.MinimaxProvider;
import com.dwinovo.animus.agent.provider.MoonshotProvider;
import com.dwinovo.animus.agent.provider.OpenAIProvider;
import com.dwinovo.animus.agent.provider.StreamAccumulator;
import com.dwinovo.animus.agent.provider.VolcengineProvider;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.platform.services.IAnimusConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Mod-global LLM client (client-side singleton). Wraps {@link HttpLlmTransport}
 * + a configured {@link LlmProvider}; SSE streaming is the default so
 * users see token-by-token progress in logs (and, in the future, in a
 * chat bubble above the entity).
 *
 * <h2>Streaming flow</h2>
 * <ol>
 *   <li>{@link #chatStreaming} builds the request body via the provider,
 *       sets {@code stream:true} + {@code stream_options.include_usage:true},
 *       and dispatches to {@link HttpLlmTransport#postSse}.</li>
 *   <li>For each SSE chunk, the provider's {@code accumulateChunk} updates
 *       a per-call {@link StreamAccumulator}.</li>
 *   <li>When the stream terminates, {@code finalizeStream} produces an
 *       {@link AssistantTurn}; the future completes with it.</li>
 *   <li>Token usage and finish reason emitted as an INFO log line.</li>
 * </ol>
 *
 * <h2>Per-call request id</h2>
 * The transport tags every request with a short {@code lr-N} id used in
 * every related log line; users can grep one ID through the whole chain
 * (HTTP → provider → agent loop) when debugging.
 */
public final class AnimusLlmClient {

    /** Suffix appended to base URLs that don't already end with it. */
    private static final String CHAT_COMPLETIONS_SUFFIX = "/chat/completions";

    private static volatile AnimusLlmClient instance;

    private final HttpLlmTransport transport = new HttpLlmTransport();
    private final LlmProvider provider;
    private final String fullUrl;
    private final String apiKey;
    private final String model;

    private AnimusLlmClient(IAnimusConfig config) {
        this.provider = pickProvider(config.getProvider());
        this.fullUrl = composeUrl(config.getBaseUrl(), provider);
        this.apiKey = config.getApiKey();
        String configured = config.getModel();
        this.model = (configured == null || configured.isBlank()) ? "gpt-5-2-mini" : configured;
        Constants.LOG.info("[animus-llm] client initialised: provider={}, model={}, url={}, streaming={}",
                provider.name(), model, fullUrl, provider.supportsStreaming());
    }

    /**
     * Compose the chat completions URL using LiteLLM's logic:
     * if the user-supplied base URL is empty, use the provider's default;
     * trim a trailing slash; if the URL doesn't already end with
     * {@code /chat/completions}, append it. Matches the behaviour of
     * each LiteLLM provider's {@code get_complete_url} method.
     */
    private static String composeUrl(String userBase, LlmProvider provider) {
        String base = (userBase == null || userBase.isBlank())
                ? provider.defaultBaseUrl() : userBase;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith(CHAT_COMPLETIONS_SUFFIX)) return base;
        return base + CHAT_COMPLETIONS_SUFFIX;
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

    public static boolean isConfigured() {
        String key = Services.CONFIG.getApiKey();
        return key != null && !key.isBlank();
    }

    /**
     * Streaming chat completion. Returns a future of the final
     * {@link AssistantTurn} (built up from all SSE chunks via the provider's
     * accumulator).
     *
     * @param messages       conversation history (provider translates to wire)
     * @param tools          tool list (provider serialises to wire shape)
     * @param systemPrompt   prepended automatically — pass empty / null to skip
     * @param onChunk        optional per-chunk callback (e.g. for live UI).
     *                       Receives the raw provider chunk JSON. May be null.
     */
    public CompletableFuture<AssistantTurn> chatStreaming(List<ConvoState.Msg> messages,
                                                           Collection<AnimusTool> tools,
                                                           String systemPrompt,
                                                           Consumer<JsonObject> onChunk) {
        // -- 1. Build wire-format messages and tool list via provider.
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

        // -- 2. Enable streaming + usage reporting (both server-side flags).
        body.addProperty("stream", true);
        JsonObject streamOpts = new JsonObject();
        streamOpts.addProperty("include_usage", true);
        body.add("stream_options", streamOpts);

        if (Constants.LOG.isDebugEnabled()) {
            Constants.LOG.debug("[animus-llm] chat start: provider={}, model={}, msgs={}, tools={}, system_prompt_chars={}",
                    provider.name(), model, wire.size(), toolList.size(),
                    systemPrompt == null ? 0 : systemPrompt.length());
        }

        // -- 3. Stream the response into an accumulator.
        long t0 = System.nanoTime();
        StreamAccumulator acc = new StreamAccumulator();
        return transport.postSse(fullUrl, apiKey, body, chunk -> {
            try {
                provider.accumulateChunk(chunk, acc);
                if (onChunk != null) onChunk.accept(chunk);
            } catch (RuntimeException ex) {
                Constants.LOG.warn("[animus-llm] accumulator failed on chunk: {}", ex.getMessage());
            }
        }).thenApply(v -> {
            AssistantTurn turn = provider.finalizeStream(acc);
            logCallSummary(t0, acc, turn);
            return turn;
        });
    }

    private void logCallSummary(long t0, StreamAccumulator acc, AssistantTurn turn) {
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        String tokens = "?";
        if (acc.usage != null) {
            int in = jsonInt(acc.usage, "prompt_tokens");
            int out = jsonInt(acc.usage, "completion_tokens");
            int total = jsonInt(acc.usage, "total_tokens");
            tokens = in + "/" + out + " (total " + total + ")";
        }
        StringBuilder toolSummary = new StringBuilder();
        for (LlmToolCall tc : turn.toolCalls()) {
            if (toolSummary.length() > 0) toolSummary.append(", ");
            toolSummary.append(tc.name());
        }
        String contentSnippet = turn.content().isEmpty() ? "<no text>"
                : truncate(turn.content().replace('\n', ' '), 120);
        Constants.LOG.info(
                "[animus-llm] chat done in {}ms, chunks={}, tokens={}, finish={}, tool_calls=[{}], content=\"{}\"",
                elapsedMs, acc.chunkCount, tokens,
                acc.finishReason == null ? "?" : acc.finishReason,
                toolSummary, contentSnippet);
    }

    private static int jsonInt(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0;
        try { return o.get(key).getAsInt(); } catch (RuntimeException ex) { return 0; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public LlmProvider provider() { return provider; }
    public String model() { return model; }

    /**
     * Map a config {@code provider} string to a concrete {@link LlmProvider}.
     *
     * <p>Accepted ids match LiteLLM's provider names plus a few Chinese
     * aliases that users naturally type:
     * <ul>
     *   <li>{@code openai} (default fallback)</li>
     *   <li>{@code deepseek} — DeepSeek (V4 / R1 family)</li>
     *   <li>{@code moonshot} / {@code kimi} — Moonshot AI (Kimi)</li>
     *   <li>{@code minimax} — MiniMax (abab / M-series)</li>
     *   <li>{@code volcengine} / {@code doubao} / {@code ark} — Doubao via Volcengine Ark</li>
     *   <li>{@code dashscope} / {@code qwen} / {@code tongyi} / {@code aliyun} — DashScope (Qwen)</li>
     * </ul>
     * Unknown values fall back to {@code openai} with a warning log.
     */
    private static LlmProvider pickProvider(String name) {
        if (name == null) return new OpenAIProvider();
        return switch (name.toLowerCase()) {
            case DeepSeekProvider.NAME -> new DeepSeekProvider();
            case MoonshotProvider.NAME, "kimi" -> new MoonshotProvider();
            case MinimaxProvider.NAME -> new MinimaxProvider();
            case VolcengineProvider.NAME, "doubao", "ark" -> new VolcengineProvider();
            case DashScopeProvider.NAME, "qwen", "tongyi", "aliyun" -> new DashScopeProvider();
            case OpenAIProvider.NAME -> new OpenAIProvider();
            default -> {
                Constants.LOG.warn("[animus-llm] unknown provider '{}', falling back to openai", name);
                yield new OpenAIProvider();
            }
        };
    }
}
