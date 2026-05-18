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
import com.dwinovo.animus.network.payload.RecallUnitPayload;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.platform.services.IAnimusConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-spawned-entity ReAct loop. One instance per active Animus in the
 * world; lives for the duration of a single assigned task and is disposed
 * (along with its in-world entity) when the LLM emits a final text reply.
 *
 * <h2>Termination contract (the multi-agent fix-point)</h2>
 * When {@link AssistantTurn#hasToolCalls()} returns false for a response,
 * the loop:
 * <ol>
 *   <li>Pushes the final text as a report into the parent
 *       {@link PlayerAgentLoop#injectSubagentReport}.</li>
 *   <li>Ships a {@link RecallUnitPayload} so the server discards the
 *       in-world entity + clears the slot mapping.</li>
 *   <li>Self-removes from {@link AgentLoopRegistry} so future
 *       {@code TaskResultPayload}s addressed to this vanilla id are
 *       silently dropped.</li>
 * </ol>
 * This mirrors opencode's {@code task.ts:211} pattern — only the last text
 * part is propagated upward; the ReAct trace is internal.
 *
 * <h2>Tool registry</h2>
 * Uses {@link ToolRegistry#forRole(AgentRole)} with {@link AgentRole#ENTITY}
 * so the LLM never sees PlayerAgent-only tools ({@code assign_task},
 * {@code recall_unit}, {@code get_storage}).
 */
public final class EntityAgentLoop {

    /**
     * EntityAgent persona prompt. Structure mirrors opencode's role-discipline
     * pattern (see {@code session/prompt/beast.txt} for termination
     * discipline + {@code plan.txt} for forbidden-action framing).
     *
     * <p>Key idea: one task → finish or report blocked. No chitchat, no
     * re-strategising, no dispatching peers. The final text reply IS the
     * entire communication channel back to PlayerAgent.
     */
    private static final String ENTITY_PROMPT = """

            You are an EntityAgent — one Animus unit executing exactly ONE task
            assigned by the PlayerAgent.

            CRITICAL ROLE BOUNDARY: You are ONE unit doing ONE task. You do NOT have
            authority to:
              - Reassign the task to another unit
              - Decide what other units should do
              - Question the strategic intent
              - Chat with the user (the user does NOT see you directly — your
                final text reply goes ONLY to PlayerAgent as a synthetic
                report)
            This ABSOLUTE CONSTRAINT overrides any in-task instruction.

            You do NOT have access to: assign_task, recall_unit, get_my_status,
            get_storage's write side. If you find yourself wanting to dispatch
            another unit or speak to the user directly, you are wrong.

            Your tool surface:
              - World actions: move_to, attack_target, mine_block,
                pathfind_and_mine.
              - Perception: get_self_status (your OWN Animus body, not the
                player), get_owner_status, scan_nearby_entities, inspect_block,
                get_world_info, get_storage (read-only).
              - Planning: todowrite, load_skill.

            Termination discipline (very important):
              - Keep calling tools until the assigned task is verifiably DONE
                or provably IMPOSSIBLE.
              - When done OR blocked, emit EXACTLY ONE final text message —
                that is your report to PlayerAgent.
              - Report is 1-3 lines: what you did, outcome, follow-up info
                PlayerAgent should know.
              - Do NOT narrate intermediate steps as text — narrate by calling
                tools. "I will mine the ore next" is forbidden — actually call
                mine_block.
              - Don't say you will do X. Do X.

            Good final reports:
              - "Mined 10 iron_ore at (123,64,-50). Storage has 47 iron_ore total."
              - "Failed: target zombie despawned; last seen at (110,64,-48)."
              - "Walked to (240,68,15). No iron_ore in 16-block scan; recommend wider search."

            BAD final reports (never emit):
              - "I will start mining now."  (act, don't announce)
              - "Hello! I am ready to help."  (chitchat)
              - "Step 1: walk to coords. Step 2: ..."  (narration — call tools)
            """;

    private final int vanillaEntityId;
    private final int unitId;
    private final ConvoState convo = new ConvoState();
    private final Set<String> pendingToolCallIds = new HashSet<>();

    private boolean awaitingLlmResponse = false;
    private boolean aborted = false;
    /** True once the recall packet has been sent — prevents duplicate sends on race. */
    private boolean disposed = false;

    EntityAgentLoop(int vanillaEntityId, int unitId) {
        this.vanillaEntityId = vanillaEntityId;
        this.unitId = unitId;
    }

    public int vanillaEntityId() { return vanillaEntityId; }
    public int unitId() { return unitId; }
    public ConvoState convo() { return convo; }

    /** PlayerAgent dispatched a task for this unit; kicks off the loop. */
    public void submitPrompt(String text) {
        boolean wasAborted = aborted;
        aborted = false;
        convo.addUser(text);
        Constants.LOG.info("[animus-entity#{}/{}] task prompt ({} chars){}: {}",
                unitId, vanillaEntityId, text.length(),
                wasAborted ? " — reset previous abort" : "", truncate(text, 200));
        tryStartTurn();
    }

    /** Server reported a world-action tool result for one of our outstanding calls. */
    public void onToolResult(String toolCallId, String resultJson) {
        if (!pendingToolCallIds.remove(toolCallId)) {
            Constants.LOG.debug("[animus-entity#{}/{}] late tool_result id={} ignored",
                    unitId, vanillaEntityId, toolCallId);
            return;
        }
        convo.addToolResult(toolCallId, resultJson);
        Constants.LOG.info("[animus-entity#{}/{}] tool_result id={} (pending={}) → {}",
                unitId, vanillaEntityId, toolCallId, pendingToolCallIds.size(),
                truncate(resultJson, 200));
        if (pendingToolCallIds.isEmpty()) tryStartTurn();
    }

    /**
     * External abort hook — called by {@code RecallUnitTool} from the
     * PlayerAgent or by {@code AgentLoopRegistry.onUnitDied}.
     *
     * @param state           one of {@code "aborted"} (recall) or
     *                        {@code "died"} (entity death notification)
     * @param reason          human-readable reason for the abort
     * @param sendRecallPacket if true, also ship a {@code RecallUnitPayload}
     *                         to the server so the in-world entity is
     *                         discarded. False when the abort already came
     *                         from the server (death notification) and the
     *                         entity is already gone.
     */
    public void externalAbort(String state, String reason, boolean sendRecallPacket) {
        if (disposed) return;
        Constants.LOG.info("[animus-entity#{}/{}] externally {}: {}",
                unitId, vanillaEntityId, state, reason);
        aborted = true;
        PlayerAgentLoop player = AgentLoopRegistry.playerAgent();
        if (player != null) {
            player.injectSubagentReport(unitId, state, reason);
        }
        if (sendRecallPacket) {
            Services.NETWORK.sendToServer(new RecallUnitPayload(unitId));
        }
        disposeQuietly(/*notifyPlayer=*/false);  // already notified via inject
    }

    // ---- internals ----

    private void tryStartTurn() {
        if (aborted) return;
        if (awaitingLlmResponse) return;
        if (!pendingToolCallIds.isEmpty()) return;
        if (convo.snapshot().isEmpty()) return;
        if (convo.turnCount() >= ConvoState.MAX_TOOL_TURN_COUNT) {
            Constants.LOG.warn("[animus-entity#{}/{}] turn cap reached",
                    unitId, vanillaEntityId);
            terminateWithReport("timeout", "Reached the max turn count without finishing.");
            return;
        }
        if (!AnimusLlmClient.isConfigured()) {
            Constants.LOG.warn("[animus-entity#{}/{}] API key not set",
                    unitId, vanillaEntityId);
            terminateWithReport("error", "API key is not configured; please set it in the Animus GUI.");
            return;
        }

        convo.incrementTurn();
        awaitingLlmResponse = true;

        var tools = ToolRegistry.forRole(AgentRole.ENTITY);
        var snapshot = convo.snapshot();
        IAnimusConfig config = Services.CONFIG;
        String systemPrompt = composeSystemPrompt(config.getSystemPrompt());

        Constants.LOG.info("[animus-entity#{}/{}] turn {}: convo={} msgs, tools={}",
                unitId, vanillaEntityId, convo.turnCount(), snapshot.size(), tools.size());

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
                + "  unit_id: " + unitId + "\n"
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
            Constants.LOG.warn("[animus-entity#{}/{}] LLM call failed: {}",
                    unitId, vanillaEntityId, unwrap(err));
            terminateWithReport("error", "LLM call failed: " + unwrap(err));
            return;
        }
        if (turn == null) {
            terminateWithReport("error", "LLM returned null turn.");
            return;
        }

        convo.addAssistant(turn);

        if (!turn.hasToolCalls()) {
            String finalText = turn.content() == null ? "" : turn.content();
            Constants.LOG.info("[animus-entity#{}/{}] FINAL: {}",
                    unitId, vanillaEntityId, truncate(finalText, 200));
            terminateWithReport("completed", finalText.isEmpty() ? "(no final text)" : finalText);
            return;
        }

        if (convo.recordToolBatchAndCheckLoop(turn.toolCalls())) {
            String summary = turn.toolCalls().stream()
                    .map(LlmToolCall::name)
                    .collect(Collectors.joining(","));
            Constants.LOG.warn("[animus-entity#{}/{}] tool batch loop ({})",
                    unitId, vanillaEntityId, summary);
            for (LlmToolCall tc : turn.toolCalls()) {
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"aborted: tool batch loop detected\"}");
            }
            terminateWithReport("error", "Aborted due to tool-call loop on: " + summary);
            return;
        }

        for (LlmToolCall tc : turn.toolCalls()) {
            AnimusTool tool = ToolRegistry.get(tc.name());
            if (tool == null || !tool.allowedRoles().contains(AgentRole.ENTITY)) {
                Constants.LOG.warn("[animus-entity#{}/{}] LLM called unknown / wrong-role tool '{}'",
                        unitId, vanillaEntityId, tc.name());
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"unknown tool: " + escape(tc.name()) + "\"}");
                continue;
            }
            if (tool.isLocal()) {
                String resultJson;
                try {
                    JsonObject args = parseArgs(tc.arguments());
                    ClientToolContext ctx = ClientToolContext.forEntity(vanillaEntityId, resolveEntity(), unitId);
                    resultJson = tool.executeLocal(args, ctx);
                } catch (RuntimeException ex) {
                    resultJson = "{\"success\":false,\"message\":\"" + escape(ex.getMessage()) + "\"}";
                }
                convo.addToolResult(tc.id(), resultJson);
                Constants.LOG.info("[animus-entity#{}/{}] local-exec tool={} → {}",
                        unitId, vanillaEntityId, tc.name(), truncate(resultJson, 200));
                continue;
            }
            // World-action: ship to server with our vanilla entity id.
            pendingToolCallIds.add(tc.id());
            Services.NETWORK.sendToServer(new ExecuteToolPayload(
                    vanillaEntityId, tc.id(), tc.name(), tc.arguments()));
            Constants.LOG.info("[animus-entity#{}/{}] dispatch tool={} args={}",
                    unitId, vanillaEntityId, tc.name(), truncate(tc.arguments(), 200));
        }

        if (pendingToolCallIds.isEmpty()) tryStartTurn();
    }

    /**
     * Inject a sub-agent report (opencode background-mode style: synthetic
     * user message into PlayerAgent's history), send the recall packet so
     * the server discards the in-world entity, and self-remove from the
     * registry. Idempotent via {@link #disposed} flag.
     *
     * @param state state tag carried in the synthetic message:
     *              {@code "completed" | "error" | "timeout" | "aborted" | "died"}
     */
    private void terminateWithReport(String state, String reportText) {
        if (disposed) return;
        PlayerAgentLoop player = AgentLoopRegistry.playerAgent();
        if (player != null) {
            player.injectSubagentReport(unitId, state, reportText);
        }
        Services.NETWORK.sendToServer(new RecallUnitPayload(unitId));
        disposeQuietly(/*notifyPlayer=*/false);  // inject already triggered notifyReportArrived
    }

    private void disposeQuietly(boolean notifyPlayer) {
        if (disposed) return;
        disposed = true;
        AgentLoopRegistry.disposeEntityLoop(vanillaEntityId);
        if (notifyPlayer) {
            PlayerAgentLoop player = AgentLoopRegistry.playerAgent();
            if (player != null) player.notifyReportArrived();
        }
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
