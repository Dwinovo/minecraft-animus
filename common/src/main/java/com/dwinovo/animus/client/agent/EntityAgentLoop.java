package com.dwinovo.animus.client.agent;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.agent.llm.AnimusLlmClient;
import com.dwinovo.animus.agent.llm.ConvoState;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.LlmToolCall;
import com.dwinovo.animus.agent.skill.SkillRegistry;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ClientToolContext;
import com.dwinovo.animus.agent.tool.ToolRegistry;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.network.payload.ExecuteToolPayload;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.platform.services.IAnimusConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-entity agent loop running on the <strong>client</strong>. One instance
 * per Animus the player talks to, keyed by the vanilla {@code entity.getId()}
 * in {@link AgentLoopRegistry}. The agent is bound to that one entity for its
 * whole lifetime — it talks directly to the owner, runs world-action tools on
 * its own body, and survives across many prompts (it is NOT a one-shot
 * sub-agent that self-destructs after a single task).
 *
 * <h2>Single-layer architecture</h2>
 * This is the deliberate roll-back from the short-lived PlayerAgent +
 * EntityAgent split. Each entity owns one conversation; the owner chats with
 * it directly (right-click → {@code EntityChatScreen}, or the Units tab Chat
 * button). The earlier two-tier design made debugging a single entity's AI
 * painful — interleaved logs, reports bouncing between agents, lifecycles
 * tearing down mid-task. Re-introduce the brain-on-the-entity model now; a
 * higher-level dispatcher can come back later once each body is solid.
 *
 * <h2>Threading rules</h2>
 * All mutations run on the client main thread:
 * <ul>
 *   <li>{@link #submitPrompt} — from {@code EntityChatScreen} (UI thread)</li>
 *   <li>{@link #onToolResult} — from {@code TaskResultPayload.handle}
 *       (already bounced onto main thread by the network channel)</li>
 *   <li>LLM response — {@link AnimusLlmClient#chatStreaming} resolves on the
 *       HTTP executor thread; {@link #bounceBackToMain} hops via
 *       {@code Minecraft.getInstance().execute} before any mutation</li>
 * </ul>
 */
public final class EntityAgentLoop {

    /**
     * Persona prompt for a single Animus body. It is the player's companion
     * unit: it acts on the world through its tools, and its plain-text replies
     * go straight to the owner in the chat GUI.
     */
    private static final String ENTITY_PROMPT = """

            You are an Animus — a single companion unit in Minecraft, owned by
            and loyal to one player. You have a physical body in the world and
            you act through it.

            How you operate:
              - World actions: move_to, attack_target, mine_block,
                pathfind_and_mine. These move and act with YOUR OWN body.
              - Perception: get_self_status (your own body), get_owner_status,
                scan_nearby_entities, scan_blocks, inspect_block, get_world_info,
                get_storage (the shared storage you deposit into).
              - Planning: todowrite, load_skill.

            Working discipline:
              - When the owner asks for something physical, DO it by calling
                tools — don't just describe what you would do. "I will mine the
                ore" is wrong; actually call mine_block.
              - Keep calling tools until the request is done or provably
                impossible, then reply with a short plain-text message to the
                owner: what you did and the outcome.
              - Your text replies are spoken to the owner. Be concise and
                natural — one short paragraph. Tool calls are silent; the owner
                only sees your text.
              - Don't narrate intermediate steps as separate chat messages —
                narrate by acting. Speak when you have a result or a question.

            Examples:
              owner: 去挖10块铁
              → pathfind_and_mine / mine_block ... (act)
              → "挖到了 10 块铁,已经放进仓库。"

              owner: 那边那个僵尸危险吗
              → scan_nearby_entities(radius=24, type_filter="hostile")
              → "西边 12 格有一只僵尸,要我去清掉吗?"
            """;

    private final int vanillaEntityId;
    private final ConvoState convo = new ConvoState();
    private final Set<String> pendingToolCallIds = new HashSet<>();

    /**
     * Prompts the owner typed while a turn was still in flight (waiting on the
     * LLM, or on outstanding tool results). They must NOT be spliced into the
     * conversation immediately: the OpenAI/DeepSeek protocol requires an
     * {@code assistant} message carrying {@code tool_calls} to be followed
     * <em>directly</em> by the matching {@code tool} results, with no
     * {@code user} message in between. So we hold them here and flush them in
     * at the next protocol-valid point (see {@link #flushBufferedPrompts}).
     */
    private final List<String> bufferedPrompts = new ArrayList<>();

    private boolean awaitingLlmResponse = false;
    private boolean aborted = false;

    /**
     * Bumped every time the owner interrupts a turn ({@link #abort}). Each LLM
     * dispatch captures the value at send time; when the streamed response
     * lands {@link #handleResponse} discards it if the generation no longer
     * matches — i.e. the turn it belongs to was cancelled. This is the
     * equivalent of Claude Code spinning up a fresh {@code AbortController} per
     * turn: an in-flight HTTP response from an interrupted turn must never be
     * spliced back into the conversation or dispatch its tool calls.
     */
    private int turnGeneration = 0;

    EntityAgentLoop(int vanillaEntityId) {
        this.vanillaEntityId = vanillaEntityId;
    }

    public int vanillaEntityId() { return vanillaEntityId; }
    public ConvoState convo() { return convo; }

    /** Owner typed a prompt in the chat GUI. */
    public void submitPrompt(String text) {
        boolean wasAborted = aborted;
        aborted = false;
        // Always buffer first; tryStartTurn() splices buffered prompts into the
        // conversation only at a protocol-valid point. If we're mid-turn (the
        // guards in tryStartTurn fire), the prompt stays buffered and gets
        // flushed once the outstanding assistant/tool round-trip completes —
        // this avoids inserting a user message between assistant(tool_calls)
        // and its tool results (which the API rejects with HTTP 400).
        boolean deferred = awaitingLlmResponse || !pendingToolCallIds.isEmpty();
        bufferedPrompts.add(text);
        Constants.LOG.info("[animus-entity#{}] user prompt ({} chars){}{}: {}",
                vanillaEntityId, text.length(),
                wasAborted ? " — reset previous abort" : "",
                deferred ? " — buffered (mid-turn)" : "",
                truncate(text, 200));
        tryStartTurn();
    }

    /** Server reported a world-action tool result for one of our outstanding calls. */
    public void onToolResult(String toolCallId, String resultJson) {
        if (!pendingToolCallIds.remove(toolCallId)) {
            Constants.LOG.debug("[animus-entity#{}] late tool_result id={} ignored",
                    vanillaEntityId, toolCallId);
            return;
        }
        convo.addToolResult(toolCallId, resultJson);
        Constants.LOG.info("[animus-entity#{}] tool_result id={} (pending={}) → {}",
                vanillaEntityId, toolCallId, pendingToolCallIds.size(),
                truncate(resultJson, 200));
        if (pendingToolCallIds.isEmpty()) tryStartTurn();
    }

    // ---- interrupt (owner-triggered, from the chat GUI "Stop" button) ----

    /** A turn is actively running: waiting on the LLM, or on world-action tool results. */
    public boolean isBusy() {
        return awaitingLlmResponse || !pendingToolCallIds.isEmpty();
    }

    /** Owner prompts are queued, waiting to flush into the conversation. */
    public boolean hasQueuedPrompts() {
        return !bufferedPrompts.isEmpty();
    }

    /** There is something an interrupt would act on — drives the Stop button's enabled state. */
    public boolean canInterrupt() {
        return isBusy() || hasQueuedPrompts();
    }

    /**
     * Owner-triggered interrupt — the chat GUI's "Stop" button. Mirrors Claude
     * Code's {@code handleCancel} (useCancelRequest.ts) two-priority rule:
     *
     * <ol>
     *   <li><b>A turn is in flight</b> → stop it. The in-flight LLM response is
     *       invalidated via {@link #turnGeneration} (discarded when it lands, so
     *       it can't dispatch tools after the fact); any world-action tool calls
     *       still awaiting a server result get a synthetic "interrupted" result
     *       so every {@code assistant(tool_calls)} keeps matching {@code tool}
     *       results and the next request stays protocol-valid. Queued prompts are
     *       <em>preserved</em> — they flush on the next submit, exactly like
     *       Claude Code keeps its message queue across an interrupt.</li>
     *   <li><b>Idle but prompts are queued</b> (e.g. typed during a turn that was
     *       just interrupted and is now held) → drop the queue. Mirrors
     *       {@code popCommandFromQueue} when there's no running task to cancel.</li>
     * </ol>
     *
     * No-op when nothing is running and nothing is queued.
     */
    public void abort() {
        if (isBusy()) {
            // Priority 1: stop the running turn.
            turnGeneration++; // any in-flight LLM response is now stale → discarded on arrival
            boolean wasAwaitingLlm = awaitingLlmResponse;
            int cancelledTools = pendingToolCallIds.size();
            awaitingLlmResponse = false;

            // Synthesize cancelled results for outstanding world-action calls so the
            // assistant(tool_calls) message keeps matching tool results. Real results
            // arriving later are dropped as "late" by onToolResult (id already removed).
            for (String id : pendingToolCallIds) {
                convo.addToolResult(id,
                        "{\"success\":false,\"message\":\"interrupted by owner\"}");
            }
            pendingToolCallIds.clear();

            // If we cut off an in-flight LLM call before its assistant turn was
            // recorded, the conversation now ends on a user message. Cap it with a
            // short assistant note so the next prompt doesn't create back-to-back
            // user messages (some backends reject those — see flushBufferedPrompts).
            if (wasAwaitingLlm && cancelledTools == 0
                    && convo.lastMessage() instanceof ConvoState.Msg.User) {
                convo.addAssistant(new AssistantTurn("(已中断)", List.of(), null));
            }

            convo.resetTurnCount();
            aborted = true;
            Constants.LOG.info("[animus-entity#{}] interrupted by owner (awaitingLlm={}, cancelledTools={}, queued={})",
                    vanillaEntityId, wasAwaitingLlm, cancelledTools, bufferedPrompts.size());
        } else if (!bufferedPrompts.isEmpty()) {
            // Priority 2: idle — drop the held queue.
            int dropped = bufferedPrompts.size();
            bufferedPrompts.clear();
            Constants.LOG.info("[animus-entity#{}] interrupt cleared {} queued prompt(s)",
                    vanillaEntityId, dropped);
        }
    }

    // ---- internals ----

    /**
     * Splice any buffered owner prompts into the conversation as a single
     * {@code user} message. Only call this at a protocol-valid point (no
     * assistant reply in flight, no tool results pending) — the callers
     * ({@link #tryStartTurn}) guarantee that. Multiple buffered prompts are
     * joined with newlines into one message to avoid back-to-back {@code user}
     * messages that some backends reject.
     */
    private void flushBufferedPrompts() {
        if (bufferedPrompts.isEmpty()) return;
        String merged = String.join("\n", bufferedPrompts);
        bufferedPrompts.clear();
        convo.addUser(merged);
        // A fresh owner directive starts a new tool-chain: restart the turn
        // counter (just log numbering now that the hard cap is gone) and clear
        // the loop-detection signature so a new directive may legitimately
        // repeat a tool batch the previous chain happened to end on.
        convo.resetTurnCount();
    }

    private void tryStartTurn() {
        if (aborted) {
            Constants.LOG.debug("[animus-entity#{}] tryStartTurn skipped: aborted", vanillaEntityId);
            return;
        }
        if (awaitingLlmResponse) {
            Constants.LOG.debug("[animus-entity#{}] tryStartTurn skipped: awaitingLlmResponse", vanillaEntityId);
            return;
        }
        if (!pendingToolCallIds.isEmpty()) {
            Constants.LOG.debug("[animus-entity#{}] tryStartTurn skipped: {} tool result(s) pending",
                    vanillaEntityId, pendingToolCallIds.size());
            return;
        }
        // Safe point: no assistant reply in flight and no tool results
        // outstanding, so the conversation ends with either a tool result or a
        // final assistant message — a user message can now be appended legally.
        flushBufferedPrompts();
        if (convo.snapshot().isEmpty()) return;
        // No hard cap on tool-call turns — a capable agent legitimately chains
        // many tasks. The only autonomous stop is the loop guard
        // (ConvoState.recordToolBatchAndCheckLoop: identical batch twice in a
        // row); genuine runaways are stopped by the owner's interrupt.
        if (!AnimusLlmClient.isConfigured()) {
            Constants.LOG.warn("[animus-entity#{}] API key not set; open the Animus GUI (X) → Settings",
                    vanillaEntityId);
            aborted = true;
            return;
        }

        convo.incrementTurn();
        awaitingLlmResponse = true;

        var tools = ToolRegistry.all();
        var snapshot = convo.snapshot();
        IAnimusConfig config = Services.CONFIG;
        String systemPrompt = composeSystemPrompt(config.getSystemPrompt());

        Constants.LOG.info("[animus-entity#{}] turn {}: convo={} msgs, tools={}",
                vanillaEntityId, convo.turnCount(), snapshot.size(), tools.size());

        // Capture the current generation; if the owner interrupts before this
        // call resolves, handleResponse sees the mismatch and discards it.
        final int gen = turnGeneration;
        AnimusLlmClient.instance().chatStreaming(snapshot, tools, systemPrompt, null)
                .whenComplete((turn, err) -> bounceBackToMain(gen, turn, err));
    }

    private String composeSystemPrompt(String basePrompt) {
        String base = basePrompt == null ? "" : basePrompt;
        String envBlock = buildEnvBlock();
        String skillsXml = SkillRegistry.instance().formatXml();

        StringBuilder sb = new StringBuilder();
        if (!base.isBlank()) sb.append(base);
        sb.append(ENTITY_PROMPT);
        if (envBlock != null) {
            sb.append("\n\n").append(envBlock);
        }
        if (!skillsXml.isEmpty()) {
            sb.append("\n\n").append(skillsXml);
        }
        return sb.toString();
    }

    private String buildEnvBlock() {
        AnimusEntity entity = resolveEntity();
        if (entity == null) return null;
        String ownerName = "unknown";
        if (entity.getOwner() != null) {
            ownerName = entity.getOwner().getName().getString();
        }
        return "<env>\n"
                + "  vanilla_entity_id: " + vanillaEntityId + "\n"
                + "  owner_name: " + ownerName + "\n"
                + "  dimension: " + entity.level().dimension().identifier() + "\n"
                + "  today: " + LocalDate.now() + "\n"
                + "</env>";
    }

    private AnimusEntity resolveEntity() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        var raw = mc.level.getEntity(vanillaEntityId);
        return raw instanceof AnimusEntity ae ? ae : null;
    }

    private void bounceBackToMain(int gen, AssistantTurn turn, Throwable err) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> handleResponse(gen, turn, err));
    }

    private void handleResponse(int gen, AssistantTurn turn, Throwable err) {
        // Owner interrupted this turn while the call was in flight: abort()
        // already settled the conversation (and, if a newer turn has since
        // started, awaitingLlmResponse belongs to *that* call). Discard wholesale
        // — do NOT touch awaitingLlmResponse here, or we'd clear the newer turn's.
        if (gen != turnGeneration) {
            Constants.LOG.info("[animus-entity#{}] discarding interrupted LLM response (gen {} != {})",
                    vanillaEntityId, gen, turnGeneration);
            return;
        }
        awaitingLlmResponse = false;

        if (err != null) {
            Constants.LOG.warn("[animus-entity#{}] LLM call failed: {}",
                    vanillaEntityId, unwrap(err));
            aborted = true;
            return;
        }
        if (turn == null) {
            Constants.LOG.warn("[animus-entity#{}] LLM returned null turn", vanillaEntityId);
            aborted = true;
            return;
        }

        convo.addAssistant(turn);

        if (!turn.hasToolCalls()) {
            // Final text reply — spoken to the owner. Chain settles; the next
            // prompt resumes the same conversation with a fresh turn count.
            if (!turn.content().isEmpty()) {
                Constants.LOG.info("[animus-entity#{}] assistant (final): {}",
                        vanillaEntityId, turn.content());
            } else {
                Constants.LOG.info("[animus-entity#{}] assistant (final, empty content)", vanillaEntityId);
            }
            convo.resetTurnCount();
            // A prompt that arrived during this final turn was buffered; now that
            // the chain has settled, start a fresh turn to answer it.
            if (!bufferedPrompts.isEmpty()) tryStartTurn();
            return;
        }

        if (convo.recordToolBatchAndCheckLoop(turn.toolCalls())) {
            String summary = turn.toolCalls().stream()
                    .map(LlmToolCall::name)
                    .collect(Collectors.joining(","));
            Constants.LOG.warn("[animus-entity#{}] tool batch loop detected ({}); stopping",
                    vanillaEntityId, summary);
            for (LlmToolCall tc : turn.toolCalls()) {
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"aborted: tool batch loop detected\"}");
            }
            aborted = true;
            return;
        }

        // Dispatch every tool call. Local tools (todowrite / load_skill /
        // perception) run synchronously here and feed the result straight into
        // the conversation; world-action tools (move_to, ...) ship to the
        // server via ExecuteToolPayload and the result comes back async via
        // TaskResultPayload.
        for (LlmToolCall tc : turn.toolCalls()) {
            AnimusTool tool = ToolRegistry.resolve(tc.name());
            if (tool == null) {
                Constants.LOG.warn("[animus-entity#{}] LLM called unknown tool '{}' (id={})",
                        vanillaEntityId, tc.name(), tc.id());
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"unknown tool: " + escape(tc.name()) + "\"}");
                continue;
            }
            if (tool.isLocal()) {
                String resultJson;
                try {
                    JsonObject args = parseArgs(tc.arguments());
                    ClientToolContext ctx = new ClientToolContext(resolveEntity(), vanillaEntityId);
                    resultJson = tool.executeLocal(args, ctx);
                } catch (RuntimeException ex) {
                    resultJson = "{\"success\":false,\"message\":\"" + escape(ex.getMessage()) + "\"}";
                    Constants.LOG.warn("[animus-entity#{}] local tool {} failed (id={}): {}",
                            vanillaEntityId, tc.name(), tc.id(), ex.getMessage());
                }
                convo.addToolResult(tc.id(), resultJson);
                Constants.LOG.info("[animus-entity#{}] local-exec tool={} id={} → {}",
                        vanillaEntityId, tc.name(), tc.id(), truncate(resultJson, 200));
                continue;
            }
            // World-action tool: ship to server with our vanilla entity id.
            // No client-side timeout — the server always returns a result, even
            // on entity death/removal (AnimusEntity.remove flushes CANCELLED
            // results synchronously), so the loop never waits forever.
            pendingToolCallIds.add(tc.id());
            Services.NETWORK.sendToServer(new ExecuteToolPayload(
                    vanillaEntityId, tc.id(), tc.name(), tc.arguments()));
            Constants.LOG.info("[animus-entity#{}] dispatch tool={} id={} args={}",
                    vanillaEntityId, tc.name(), tc.id(), truncate(tc.arguments(), 200));
        }

        // If nothing is awaiting a server round-trip (all local-exec / rejected),
        // kick the next turn immediately so the LLM sees the new tool_results.
        if (pendingToolCallIds.isEmpty()) tryStartTurn();
    }

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
