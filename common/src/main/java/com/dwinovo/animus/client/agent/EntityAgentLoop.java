package com.dwinovo.animus.client.agent;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.agent.llm.AnimusLlmClient;
import com.dwinovo.animus.agent.llm.ConvoState;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.LlmToolCall;
import com.dwinovo.animus.agent.skill.SkillRegistry;
import com.dwinovo.animus.agent.tool.AgentRole;
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
import java.util.HashSet;
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

    /**
     * Server-result watchdog timeout. If {@link #pendingToolCallIds} stays
     * non-empty without any add/remove churn for this long, we assume the
     * server hung / packet was lost / entity died without our death hook
     * firing, and we abort the in-flight turn so the loop isn't blocked
     * forever. The conversation stays intact — the next prompt resumes it.
     */
    private static final long STALE_PENDING_TIMEOUT_MS = 30_000L;

    private final int vanillaEntityId;
    private final ConvoState convo = new ConvoState();
    private final Set<String> pendingToolCallIds = new HashSet<>();

    private boolean awaitingLlmResponse = false;
    private boolean aborted = false;
    /** Wall-clock of the last {@link #pendingToolCallIds} mutation. 0 = inactive. */
    private long lastPendingChangeMs = 0L;

    EntityAgentLoop(int vanillaEntityId) {
        this.vanillaEntityId = vanillaEntityId;
    }

    public int vanillaEntityId() { return vanillaEntityId; }
    public ConvoState convo() { return convo; }

    /** Owner typed a prompt in the chat GUI. */
    public void submitPrompt(String text) {
        boolean wasAborted = aborted;
        aborted = false;
        convo.addUser(text);
        Constants.LOG.info("[animus-entity#{}] user prompt ({} chars){}: {}",
                vanillaEntityId, text.length(),
                wasAborted ? " — reset previous abort" : "", truncate(text, 200));
        tryStartTurn();
    }

    /** Server reported a world-action tool result for one of our outstanding calls. */
    public void onToolResult(String toolCallId, String resultJson) {
        if (!pendingToolCallIds.remove(toolCallId)) {
            Constants.LOG.debug("[animus-entity#{}] late tool_result id={} ignored",
                    vanillaEntityId, toolCallId);
            return;
        }
        lastPendingChangeMs = System.currentTimeMillis();
        convo.addToolResult(toolCallId, resultJson);
        Constants.LOG.info("[animus-entity#{}] tool_result id={} (pending={}) → {}",
                vanillaEntityId, toolCallId, pendingToolCallIds.size(),
                truncate(resultJson, 200));
        if (pendingToolCallIds.isEmpty()) tryStartTurn();
    }

    /**
     * Per-client-tick poll. Drives the {@link #STALE_PENDING_TIMEOUT_MS}
     * watchdog — aborts the in-flight turn when a server-side task result has
     * been outstanding without progress for too long. Fanned out by
     * {@link AgentLoopRegistry#tickAll()} from the loader's client-tick event.
     */
    public void tick() {
        if (pendingToolCallIds.isEmpty()) return;
        long elapsed = System.currentTimeMillis() - lastPendingChangeMs;
        if (elapsed >= STALE_PENDING_TIMEOUT_MS) {
            Constants.LOG.warn("[animus-entity#{}] stale watchdog fired ({} pending for {}s); aborting turn",
                    vanillaEntityId, pendingToolCallIds.size(), elapsed / 1000);
            for (String id : pendingToolCallIds) {
                convo.addToolResult(id,
                        "{\"success\":false,\"message\":\"aborted: no server result within "
                                + (STALE_PENDING_TIMEOUT_MS / 1000) + "s (server hang / packet loss / entity gone)\"}");
            }
            pendingToolCallIds.clear();
            aborted = true;
        }
    }

    // ---- internals ----

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
        if (convo.snapshot().isEmpty()) return;
        if (convo.turnCount() >= ConvoState.MAX_TOOL_TURN_COUNT) {
            Constants.LOG.warn("[animus-entity#{}] turn cap ({}) reached, stopping",
                    vanillaEntityId, ConvoState.MAX_TOOL_TURN_COUNT);
            aborted = true;
            return;
        }
        if (!AnimusLlmClient.isConfigured()) {
            Constants.LOG.warn("[animus-entity#{}] API key not set; open the Animus GUI (X) → Settings",
                    vanillaEntityId);
            aborted = true;
            return;
        }

        convo.incrementTurn();
        awaitingLlmResponse = true;

        var tools = ToolRegistry.forRole(AgentRole.ENTITY);
        var snapshot = convo.snapshot();
        IAnimusConfig config = Services.CONFIG;
        String systemPrompt = composeSystemPrompt(config.getSystemPrompt());

        Constants.LOG.info("[animus-entity#{}] turn {}: convo={} msgs, tools={}",
                vanillaEntityId, convo.turnCount(), snapshot.size(), tools.size());

        AnimusLlmClient.instance().chatStreaming(snapshot, tools, systemPrompt, null)
                .whenComplete(this::bounceBackToMain);
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

    private void bounceBackToMain(AssistantTurn turn, Throwable err) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> handleResponse(turn, err));
    }

    private void handleResponse(AssistantTurn turn, Throwable err) {
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
            if (tool == null || !tool.allowedRoles().contains(AgentRole.ENTITY)) {
                Constants.LOG.warn("[animus-entity#{}] LLM called unknown / wrong-role tool '{}' (id={})",
                        vanillaEntityId, tc.name(), tc.id());
                String reason = (tool == null)
                        ? "unknown tool: " + escape(tc.name())
                        : "tool '" + escape(tc.name()) + "' is not callable from this Animus";
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"" + reason + "\"}");
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
            pendingToolCallIds.add(tc.id());
            lastPendingChangeMs = System.currentTimeMillis();
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
