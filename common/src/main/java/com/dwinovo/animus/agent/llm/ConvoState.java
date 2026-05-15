package com.dwinovo.animus.agent.llm;

import com.dwinovo.animus.agent.provider.AssistantTurn;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Per-entity conversation history + loop-detection bookkeeping. Lives on the
 * **client** side now (LLM moved off-server in the per-player-pays-tokens
 * refactor), accessed solely from the client main thread — no synchronisation
 * needed.
 *
 * <h2>Storage type</h2>
 * Plain provider-agnostic DTOs ({@link AssistantTurn} et al.) — no SDK
 * classes leak through. The provider layer translates these to / from wire
 * JSON when building requests and parsing responses.
 *
 * <h2>Loop detection</h2>
 * {@link #recordToolBatchAndCheckLoop} compares the sorted tool-name list of
 * each LLM response against the previous one. Two identical batches in a row
 * triggers an abort — borrowed from TouhouLittleMaid's
 * {@code MAX_REPEAT_TOOL_BATCH_COUNT = 2}, which they validated against real
 * GPT-4 behaviour.
 */
public final class ConvoState {

    /** Hard cap on LLM calls per chain. Borrowed from TLM as a sane default. */
    public static final int MAX_TOOL_TURN_COUNT = 16;
    /** Identical-batch repeats before we declare a loop. */
    public static final int MAX_REPEAT_TOOL_BATCH_COUNT = 2;

    /** Tagged union for conversation history. */
    public sealed interface Msg permits Msg.User, Msg.Assistant, Msg.Tool {
        record User(String content) implements Msg {}
        record Assistant(AssistantTurn turn) implements Msg {}
        record Tool(String toolCallId, String content) implements Msg {}
    }

    private final List<Msg> messages = new ArrayList<>();
    private int turnCount = 0;
    private String lastBatchSignature = "";
    private int repeatedBatchCount = 0;

    public void addUser(String content) {
        messages.add(new Msg.User(content));
    }

    public void addAssistant(AssistantTurn turn) {
        messages.add(new Msg.Assistant(turn));
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

    /** Wipe history. Called on explicit reset (Phase-2: /animus reset command). */
    public void clear() {
        messages.clear();
        resetTurnCount();
    }
}
