package com.dwinovo.animus.agent.llm;

import com.dwinovo.animus.agent.provider.AssistantTurn;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-entity conversation history. Lives on the **client** side now (LLM moved
 * off-server in the per-player-pays-tokens refactor), accessed solely from the
 * client main thread — no synchronisation needed.
 *
 * <h2>Storage type</h2>
 * Plain provider-agnostic DTOs ({@link AssistantTurn} et al.) — no SDK
 * classes leak through. The provider layer translates these to / from wire
 * JSON when building requests and parsing responses.
 *
 * <h2>No loop guard, no turn cap</h2>
 * There is intentionally no autonomous stop: a capable agent legitimately
 * chains many tasks, and retrying a timed-out task repeats the exact same
 * tool call — a signature-based loop detector kept killing that correct
 * recovery (it aborted a {@code move_to} resume after a mid-journey timeout).
 * Runaways are stopped by the owner's interrupt; {@link #turnCount} is kept
 * purely for log numbering.
 */
public final class ConvoState {

    /** Tagged union for conversation history. */
    public sealed interface Msg permits Msg.User, Msg.Assistant, Msg.Tool {
        record User(String content) implements Msg {}
        record Assistant(AssistantTurn turn) implements Msg {}
        record Tool(String toolCallId, String content) implements Msg {}
    }

    private final List<Msg> messages = new ArrayList<>();
    private int turnCount = 0;

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

    /** Most recent message, or {@code null} when the history is empty. Used by
     *  the agent loop's interrupt path to keep the conversation protocol-valid
     *  (avoid leaving a trailing {@code user} message after an aborted turn). */
    public Msg lastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    public int turnCount() { return turnCount; }
    public void incrementTurn() { turnCount++; }

    /**
     * Called when a final text response arrives (no tool_calls). The chain
     * is settled; the next user message starts a fresh log-numbering count.
     */
    public void resetTurnCount() {
        turnCount = 0;
    }

    /** Wipe history. Called on explicit reset (Phase-2: /animus reset command). */
    public void clear() {
        messages.clear();
        resetTurnCount();
    }
}
