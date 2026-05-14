package com.dwinovo.animus.agent.llm;

import com.openai.models.chat.completions.ChatCompletionMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Per-entity conversation history + loop-detection bookkeeping. Lives on the
 * server side only (LLM never runs client-side), accessed solely from the
 * server main thread — no synchronisation needed.
 *
 * <h2>Message shape</h2>
 * We don't store OpenAI's {@code ChatCompletionMessageParam} (sum type) for
 * user / tool messages because constructing the sum-type wrappers eagerly is
 * awkward. Instead we keep our own tagged-union {@link Msg} and translate to
 * SDK types at request build time inside {@link AnimusLlmClient}.
 *
 * <p>For assistant messages we DO store the original {@link ChatCompletionMessage}
 * — that response object has the tool_call list with the exact ids and
 * arguments the API expects to see echoed back, and re-deriving it from
 * fields would be error-prone. The SDK's {@code toParam()} flips it into
 * the right request shape lazily.
 *
 * <h2>Loop detection</h2>
 * {@link #recordToolBatchAndCheckLoop} compares the sorted tool-name list of
 * each LLM response against the previous one. Two identical batches in a row
 * triggers an abort — borrowed from TouhouLittleMaid's
 * {@code MAX_REPEAT_TOOL_BATCH_COUNT = 2}, which they validated against real
 * GPT-4 behaviour. Cheap heuristic that catches the common "model loops on
 * the same failure" pathology without false-positives on legitimate retries.
 */
public final class ConvoState {

    /** Hard cap on LLM calls per chain. Borrowed from TLM as a sane default. */
    public static final int MAX_TOOL_TURN_COUNT = 16;
    /** Identical-batch repeats before we declare a loop. */
    public static final int MAX_REPEAT_TOOL_BATCH_COUNT = 2;

    /** Tagged union for conversation history. */
    public sealed interface Msg permits Msg.User, Msg.Assistant, Msg.Tool {
        record User(String content) implements Msg {}
        record Assistant(ChatCompletionMessage raw) implements Msg {}
        record Tool(String toolCallId, String content) implements Msg {}
    }

    private final List<Msg> messages = new ArrayList<>();
    private int turnCount = 0;
    private String lastBatchSignature = "";
    private int repeatedBatchCount = 0;

    public void addUser(String content) {
        messages.add(new Msg.User(content));
    }

    public void addAssistant(ChatCompletionMessage raw) {
        messages.add(new Msg.Assistant(raw));
    }

    public void addToolResult(String toolCallId, String content) {
        messages.add(new Msg.Tool(toolCallId, content));
    }

    public List<Msg> snapshot() {
        return List.copyOf(messages);
    }

    public int turnCount() { return turnCount; }
    public void incrementTurn() { turnCount++; }

    /**
     * Called when a final text response arrives (no tool_calls). The chain
     * is settled; the next user message starts a fresh count, so cap doesn't
     * accumulate across separate user prompts.
     */
    public void resetTurnCount() {
        turnCount = 0;
        lastBatchSignature = "";
        repeatedBatchCount = 0;
    }

    /**
     * Record the tool batch from an assistant response and report whether
     * we've now seen the same batch enough times to bail out.
     *
     * @param toolNames names of tools in this assistant turn, any order
     * @return {@code true} → abort, {@code false} → continue
     */
    public boolean recordToolBatchAndCheckLoop(List<String> toolNames) {
        String sig = toolNames.stream().sorted().collect(Collectors.joining(","));
        if (sig.equals(lastBatchSignature)) {
            repeatedBatchCount++;
            return repeatedBatchCount >= MAX_REPEAT_TOOL_BATCH_COUNT;
        }
        lastBatchSignature = sig;
        repeatedBatchCount = 1;
        return false;
    }

    /** Wipe history. Called on entity removal or explicit reset. */
    public void clear() {
        messages.clear();
        resetTurnCount();
    }
}
