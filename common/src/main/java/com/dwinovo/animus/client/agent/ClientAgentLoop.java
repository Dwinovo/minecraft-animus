package com.dwinovo.animus.client.agent;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.agent.llm.AnimusLlmClient;
import com.dwinovo.animus.agent.llm.ConvoState;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.LlmToolCall;
import com.dwinovo.animus.agent.skill.SkillRegistry;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ToolRegistry;
import com.dwinovo.animus.network.payload.ExecuteToolPayload;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.platform.services.IAnimusConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
        boolean wasAborted = aborted;
        aborted = false;
        convo.addUser(text);
        Constants.LOG.info("[animus-agent#{}] user prompt ({} chars){}: {}",
                entityId, text.length(), wasAborted ? " — reset previous abort" : "",
                truncate(text, 200));
        tryStartTurn();
    }

    /**
     * The server reported back on a tool execution. Threaded through the
     * network channel so we're already on the client main thread.
     */
    public void onToolResult(String toolCallId, String resultJson) {
        if (!pendingToolCallIds.remove(toolCallId)) {
            Constants.LOG.debug("[animus-agent#{}] tool_result for unknown id={} (late or spurious); ignoring",
                    entityId, toolCallId);
            return;
        }
        convo.addToolResult(toolCallId, resultJson);
        Constants.LOG.info("[animus-agent#{}] tool_result id={} (pending now={}) → {}",
                entityId, toolCallId, pendingToolCallIds.size(),
                truncate(resultJson, 200));
        if (pendingToolCallIds.isEmpty()) {
            Constants.LOG.debug("[animus-agent#{}] all pending tool results in — starting next turn",
                    entityId);
            tryStartTurn();
        }
    }

    // ---- internals ----

    private void tryStartTurn() {
        if (aborted) {
            Constants.LOG.debug("[animus-agent#{}] tryStartTurn skipped: aborted", entityId);
            return;
        }
        if (awaitingLlmResponse) {
            Constants.LOG.debug("[animus-agent#{}] tryStartTurn skipped: awaitingLlmResponse", entityId);
            return;
        }
        if (!pendingToolCallIds.isEmpty()) {
            Constants.LOG.debug("[animus-agent#{}] tryStartTurn skipped: {} tool result(s) still pending",
                    entityId, pendingToolCallIds.size());
            return;
        }
        if (convo.snapshot().isEmpty()) {
            Constants.LOG.debug("[animus-agent#{}] tryStartTurn skipped: empty convo", entityId);
            return;
        }
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
        String systemPrompt = composeSystemPrompt(config.getSystemPrompt());

        Constants.LOG.info("[animus-agent#{}] turn {}: convo={} msgs, tools={}, skills={}",
                entityId, convo.turnCount(), snapshot.size(), tools.size(),
                SkillRegistry.instance().size());

        AnimusLlmClient.instance().chatStreaming(snapshot, tools, systemPrompt, null)
                .whenComplete(this::bounceBackToMain);
    }

    /**
     * Compose the per-turn system prompt: the user-configured base prompt
     * plus the dynamic {@code <available_skills>} XML block. Recomputed
     * every turn so skill files added / removed between turns become
     * visible immediately (no client restart needed).
     */
    private static String composeSystemPrompt(String basePrompt) {
        String base = basePrompt == null ? "" : basePrompt;
        String skillsXml = SkillRegistry.instance().formatXml();
        if (skillsXml.isEmpty()) return base;
        if (base.isBlank()) return skillsXml;
        return base + "\n\n" + skillsXml;
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
                Constants.LOG.info("[animus-agent#{}] assistant (final): {}",
                        entityId, turn.content());
            } else {
                Constants.LOG.info("[animus-agent#{}] assistant (final, empty content)", entityId);
            }
            convo.resetTurnCount();
            return;
        }

        // Loop guard. Signature includes args so iterating across distinct
        // positions (e.g. mining a vein) doesn't trip the detector — see
        // ConvoState.recordToolBatchAndCheckLoop javadoc.
        if (convo.recordToolBatchAndCheckLoop(turn.toolCalls())) {
            String summary = turn.toolCalls().stream()
                    .map(LlmToolCall::name)
                    .collect(Collectors.joining(","));
            Constants.LOG.warn("[animus-agent#{}] tool batch loop detected ({}); stopping",
                    entityId, summary);
            for (LlmToolCall tc : turn.toolCalls()) {
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"aborted: tool batch loop detected\"}");
            }
            aborted = true;
            return;
        }

        // Dispatch every tool call. Local tools (todowrite / load_skill) run
        // synchronously here and feed the result straight into the conversation;
        // world-action tools (move_to, ...) ship to the server via ExecuteToolPayload
        // and the result comes back asynchronously via TaskResultPayload.
        int dispatched = 0, executedLocal = 0, rejected = 0;
        for (LlmToolCall tc : turn.toolCalls()) {
            AnimusTool tool = ToolRegistry.get(tc.name());
            if (tool == null) {
                rejected++;
                Constants.LOG.warn("[animus-agent#{}] LLM called unknown tool '{}' (id={}); auto-failing",
                        entityId, tc.name(), tc.id());
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"unknown tool: " + escape(tc.name()) + "\"}");
                continue;
            }

            if (tool.isLocal()) {
                String resultJson;
                try {
                    JsonObject args = parseArgs(tc.arguments());
                    resultJson = tool.executeLocal(args);
                } catch (RuntimeException ex) {
                    resultJson = "{\"success\":false,\"message\":\"" + escape(ex.getMessage()) + "\"}";
                    Constants.LOG.warn("[animus-agent#{}] local tool {} failed (id={}): {}",
                            entityId, tc.name(), tc.id(), ex.getMessage());
                }
                convo.addToolResult(tc.id(), resultJson);
                executedLocal++;
                Constants.LOG.info("[animus-agent#{}] local-exec tool={} id={} args={} → {}",
                        entityId, tc.name(), tc.id(),
                        truncate(tc.arguments(), 120), truncate(resultJson, 200));
                continue;
            }

            // World-action tool: ship to server, wait for TaskResultPayload.
            pendingToolCallIds.add(tc.id());
            Services.NETWORK.sendToServer(new ExecuteToolPayload(
                    entityId, tc.id(), tc.name(), tc.arguments()));
            Constants.LOG.info("[animus-agent#{}] dispatch tool={} id={} args={}",
                    entityId, tc.name(), tc.id(), truncate(tc.arguments(), 200));
            dispatched++;
        }
        Constants.LOG.debug("[animus-agent#{}] dispatched {}, local {}, rejected {}, pending {} after this turn",
                entityId, dispatched, executedLocal, rejected, pendingToolCallIds.size());

        // If nothing is awaiting a server round-trip (all local-exec or all rejected),
        // kick the next turn immediately so the LLM sees the new tool_results.
        if (pendingToolCallIds.isEmpty()) {
            tryStartTurn();
        }
    }

    /**
     * Parse the {@code tc.arguments()} JSON string into a {@link JsonObject}
     * for local-tool execution. Empty-string arguments are treated as an
     * empty object (matches OpenAI's convention for zero-arg tool calls).
     */
    private static JsonObject parseArgs(String raw) {
        if (raw == null || raw.isBlank()) return new JsonObject();
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid arguments JSON: " + ex.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
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
