package com.dwinovo.animus.agent.provider;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-streaming-call scratchpad: accumulates the partial state of an
 * assistant response as SSE chunks arrive, then gets finalised by the
 * provider into a complete {@link AssistantTurn}.
 *
 * <h2>Why a builder DTO instead of pure functional folding</h2>
 * Tool-call arguments arrive as JSON-string fragments across many chunks
 * (OpenAI's actual behaviour — the model can't emit the complete argument
 * object until it's generated all tokens). Accumulating into mutable
 * {@link StringBuilder}s indexed by {@code tool_calls[].index} is by far
 * the simplest correct implementation. The DTO is per-call and never
 * shared across threads.
 *
 * <h2>What gets stored</h2>
 * <ul>
 *   <li>{@link #content} — concatenation of every {@code delta.content}
 *       fragment</li>
 *   <li>{@link #toolCalls} — partial tool calls indexed by their stream
 *       position (the {@code index} field in each delta tool_call entry)</li>
 *   <li>{@link #extraBuffers} — per-field accumulator for non-standard
 *       string fields (e.g. DeepSeek's {@code reasoning_content}, captured
 *       by {@link OpenAIProvider#captureChunkExtras})</li>
 *   <li>{@link #finishReason} — set by the last meaningful chunk
 *       ({@code "stop"} / {@code "tool_calls"} / {@code "length"} / ...)</li>
 *   <li>{@link #usage} — set by the final usage-bearing chunk when the
 *       request enables {@code stream_options.include_usage:true}</li>
 * </ul>
 *
 * <p>Chunks counted ({@link #chunkCount}) for debug logging — useful to
 * see at a glance how chatty a backend was on a given response.
 */
public final class StreamAccumulator {

    public final StringBuilder content = new StringBuilder();
    public final Map<Integer, ToolCallBuilder> toolCalls = new LinkedHashMap<>();
    public final Map<String, StringBuilder> extraBuffers = new LinkedHashMap<>();

    public String finishReason;
    public JsonObject usage;

    public int chunkCount;

    /** Look up (or create) the tool-call builder for the given stream index. */
    public ToolCallBuilder toolCallAt(int index) {
        return toolCalls.computeIfAbsent(index, k -> new ToolCallBuilder());
    }

    /** Append a fragment to the named extras buffer (lazy creation). */
    public void appendExtra(String key, String fragment) {
        if (fragment == null || fragment.isEmpty()) return;
        extraBuffers.computeIfAbsent(key, k -> new StringBuilder()).append(fragment);
    }

    public static final class ToolCallBuilder {
        /** Tool call id from the LLM. Set on first chunk that carries it. */
        public String id;
        /** Tool name. Set on first chunk that carries it. */
        public String name;
        /** Concatenated JSON-string fragments of {@code arguments}. */
        public final StringBuilder arguments = new StringBuilder();
    }
}
