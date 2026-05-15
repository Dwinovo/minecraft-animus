package com.dwinovo.animus.client.agent;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.agent.llm.AnimusLlmClient;
import com.dwinovo.animus.agent.llm.ConvoState;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.LlmToolCall;
import com.dwinovo.animus.agent.tool.ToolRegistry;
import com.dwinovo.animus.network.payload.ExecuteToolPayload;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.platform.services.IAnimusConfig;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-entity agent loop running on the **client**. Coordinates:
 * <ol>
 *   <li>Player prompts (from {@link com.dwinovo.animus.client.screen.PromptScreen})</li>
 *   <li>LLM calls (via {@link AnimusLlmClient}, using the player's own API key)</li>
 *   <li>Tool-call dispatch to the server ({@link ExecuteToolPayload})</li>
 *   <li>Tool result intake ({@code TaskResultPayload} → {@link #onToolResult})</li>
 *   <li>Next LLM turn when all pending results are back</li>
 * </ol>
 *
 * <h2>Why client-side</h2>
 * Token consumption is billed to each player's own API key. Server owner
 * doesn't pay for what individual players' Animuses do; players don't have
 * to trust the server with their API key. Server stays a pure task executor
 * + result router.
 *
 * <h2>Threading rules</h2>
 * All mutations run on the client main thread:
 * <ul>
 *   <li>{@link #submitPrompt} — called from {@code PromptScreen.onSend} (UI thread)</li>
 *   <li>{@link #onToolResult} — called from {@code TaskResultPayload.handle}
 *       (already bounced onto main thread by the network channel)</li>
 *   <li>LLM response — {@link AnimusLlmClient#chat} resolves on the HTTP
 *       executor's internal thread; {@link #bounceBackToMain} hops via
 *       {@code Minecraft.getInstance().execute} before any mutation</li>
 * </ul>
 *
 * <h2>State flags</h2>
 * <ul>
 *   <li>{@code awaitingLlmResponse} — true between request send and
 *       response bounce-back; prevents double turns from interleaved
 *       events</li>
 *   <li>{@code pendingToolCallIds} — ids we've shipped via
 *       {@link ExecuteToolPayload} and haven't seen results for yet; when
 *       empty, we may start the next turn</li>
 *   <li>{@code aborted} — sticky after turn cap, loop detection, or LLM
 *       call failure; cleared by the next user prompt</li>
 * </ul>
 */
public final class ClientAgentLoop {

    private final int entityId;
    private final ConvoState convo = new ConvoState();
    private final Set<String> pendingToolCallIds = new HashSet<>();

    private boolean awaitingLlmResponse = false;
    private boolean aborted = false;

    ClientAgentLoop(int entityId) {
        this.entityId = entityId;
    }

    public ConvoState convo() { return convo; }

    /** Player typed a prompt and hit Send. */
    public void submitPrompt(String text) {
        aborted = false;
        convo.addUser(text);
        Constants.LOG.debug("[animus-agent#{}] user: {}", entityId, text);
        tryStartTurn();
    }

    /**
     * The server reported back on a tool execution. Threaded through the
     * network channel so we're already on the client main thread.
     */
    public void onToolResult(String toolCallId, String resultJson) {
        if (!pendingToolCallIds.remove(toolCallId)) {
            // Late / spurious result; ignore silently.
            return;
        }
        convo.addToolResult(toolCallId, resultJson);
        Constants.LOG.debug("[animus-agent#{}] tool_result id={} → {}",
                entityId, toolCallId, resultJson);
        if (pendingToolCallIds.isEmpty()) {
            tryStartTurn();
        }
    }

    // ---- internals ----

    private void tryStartTurn() {
        if (aborted) return;
        if (awaitingLlmResponse) return;
        if (!pendingToolCallIds.isEmpty()) return;
        if (convo.snapshot().isEmpty()) return;
        if (convo.turnCount() >= ConvoState.MAX_TOOL_TURN_COUNT) {
            Constants.LOG.warn("[animus-agent#{}] turn cap ({}) reached, stopping",
                    entityId, ConvoState.MAX_TOOL_TURN_COUNT);
            aborted = true;
            return;
        }
        if (!AnimusLlmClient.isConfigured()) {
            Constants.LOG.warn("[animus-agent#{}] API key not set in config; edit config/animus.json (Fabric) or animus-common.toml (NeoForge)",
                    entityId);
            aborted = true;
            return;
        }

        convo.incrementTurn();
        awaitingLlmResponse = true;

        var tools = ToolRegistry.all();
        var snapshot = convo.snapshot();
        IAnimusConfig config = Services.CONFIG;
        String systemPrompt = config.getSystemPrompt();

        Constants.LOG.debug("[animus-agent#{}] LLM call turn={} msgs={} tools={}",
                entityId, convo.turnCount(), snapshot.size(), tools.size());

        AnimusLlmClient.instance().chat(snapshot, tools, systemPrompt)
                .whenComplete(this::bounceBackToMain);
    }

    private void bounceBackToMain(AssistantTurn turn, Throwable err) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> handleResponse(turn, err));
    }

    private void handleResponse(AssistantTurn turn, Throwable err) {
        awaitingLlmResponse = false;

        if (err != null) {
            Constants.LOG.warn("[animus-agent#{}] LLM call failed: {}",
                    entityId, unwrap(err));
            aborted = true;
            return;
        }
        if (turn == null) {
            Constants.LOG.warn("[animus-agent#{}] LLM returned null turn", entityId);
            aborted = true;
            return;
        }

        convo.addAssistant(turn);

        if (!turn.hasToolCalls()) {
            // Final text reply — chain settled, fresh turn count for next prompt.
            if (!turn.content().isEmpty()) {
                Constants.LOG.info("[animus-agent#{}] assistant: {}", entityId, turn.content());
            }
            convo.resetTurnCount();
            return;
        }

        // Loop guard.
        List<String> names = new ArrayList<>(turn.toolCalls().size());
        for (LlmToolCall tc : turn.toolCalls()) names.add(tc.name());
        if (convo.recordToolBatchAndCheckLoop(names)) {
            Constants.LOG.warn("[animus-agent#{}] tool batch loop detected ({}); stopping",
                    entityId, String.join(",", names));
            // Synthetic results so the convo stays consistent if user resumes.
            for (LlmToolCall tc : turn.toolCalls()) {
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"aborted: tool batch loop detected\"}");
            }
            aborted = true;
            return;
        }

        // Dispatch every tool call to the server.
        for (LlmToolCall tc : turn.toolCalls()) {
            if (ToolRegistry.get(tc.name()) == null) {
                // Unknown tool — synthesise a local failure result so we
                // don't ship a doomed request and don't block waiting.
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"unknown tool: " + escape(tc.name()) + "\"}");
                continue;
            }
            pendingToolCallIds.add(tc.id());
            Services.NETWORK.sendToServer(new ExecuteToolPayload(
                    entityId, tc.id(), tc.name(), tc.arguments()));
            Constants.LOG.debug("[animus-agent#{}] dispatch tool={} id={} args={}",
                    entityId, tc.name(), tc.id(), tc.arguments());
        }

        // If every tool was rejected locally (unknown), there's nothing pending;
        // kick the next turn so the LLM sees the synthetic failures.
        if (pendingToolCallIds.isEmpty()) {
            tryStartTurn();
        }
    }

    private static String unwrap(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur != cur.getCause()) cur = cur.getCause();
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
