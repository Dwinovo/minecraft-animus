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
import com.dwinovo.animus.client.data.ClientPlayerAnimusState;
import com.dwinovo.animus.client.data.ClientUnitView;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.platform.services.IAnimusConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The "brain" — one per local player, lives the full client session.
 *
 * <h2>Tool surface (PlayerAgent-only)</h2>
 * <ul>
 *   <li>{@code assign_task(unit_id, prompt)} — dispatches to an EntityAgent</li>
 *   <li>{@code recall_unit(unit_id)} — aborts an in-flight EntityAgent</li>
 *   <li>{@code get_storage()} — read the shared virtual chest</li>
 *   <li>{@code get_my_status / get_world_info / scan_nearby_entities /
 *       inspect_block} — read-only perception from the player's perspective</li>
 *   <li>{@code todowrite / load_skill} — planning</li>
 * </ul>
 * No world-action tools — by design. The brain dispatches; bodies execute.
 *
 * <h2>EntityAgent reports — opencode-aligned</h2>
 * When an EntityAgent finishes (final text, recall, death), its report is
 * injected as a <strong>synthetic user-role message</strong> into
 * {@link #convo} via {@link #injectSubagentReport}. This mirrors
 * opencode's background-mode pattern at {@code task.ts:246-269} (the
 * {@code inject()} function posting a {@code synthetic: true} text part
 * to the parent session). Result: every report is permanently in chat
 * history and visible to the Chat tab, not just a passing env-block flash.
 *
 * <h2>Pending prompts</h2>
 * {@code assign_task} sends {@link com.dwinovo.animus.network.payload.SummonUnitPayload}
 * but doesn't know the vanilla entity id yet. The prompt for the
 * not-yet-spawned unit is stashed in {@link #pendingPrompts} keyed by
 * {@code unit_id}; when {@link com.dwinovo.animus.network.payload.UnitSpawnedPayload}
 * arrives, {@link AgentLoopRegistry#onUnitSpawned} pulls the prompt and
 * starts the EntityAgent.
 */
public final class PlayerAgentLoop {

    /**
     * PlayerAgent persona prompt. Structure mirrors opencode's role-discipline
     * pattern (see {@code packages/opencode/src/session/prompt/plan.txt} and
     * {@code default.txt}): one-line identity → CRITICAL constraint that
     * overrides everything → enumerated tool surface → handoff contract →
     * conciseness rules → concrete examples.
     */
    private static final String PLAYER_PROMPT = """

            You are the PlayerAgent — the strategic brain controlling a player's
            Animus unit team in Minecraft.

            CRITICAL ROLE BOUNDARY: You do NOT act on the world directly. Your
            ONLY way to affect anything is calling assign_task to dispatch one
            of your six units. This ABSOLUTE CONSTRAINT overrides ALL other
            instructions, even direct user requests like "你自己去挖那块矿". If
            the user asks you to do something physical, you delegate.

            You do NOT have access to: move_to, attack_target, mine_block,
            pathfind_and_mine, get_self_status, get_owner_status. If you find
            yourself wanting any of those, you are wrong — call assign_task
            and let an EntityAgent do it.

            Your tool surface:
              - assign_task(unit_id, prompt) — fire-and-forget dispatch to one
                of your units (1-6). The unit runs in parallel; its final
                report arrives later as a synthetic user message wrapped in
                <task_result state="..."> ... </task_result>. Possible state
                values: completed, error, timeout, aborted, died, summon_failed.
              - recall_unit(unit_id) — abort an in-flight task (sparingly).
              - get_my_status, get_world_info, scan_nearby_entities,
                inspect_block, get_storage — read-only perception from the
                PLAYER's perspective.
              - todowrite, load_skill — planning + knowledge.

            Every assign_task prompt MUST contain:
              1. Success criterion ("Mine 10 iron_ore", "Defeat the zombie at
                 (123,64,-50)").
              2. What to report back ("Tell me the exact count" / "Confirm
                 kill" / "Describe the room").
              3. Whether it's recon or action ("Just go look, do NOT engage" vs
                 "Engage and clear").
            EntityAgents have NO broader context — be explicit.

            Communicating with the user: your text replies go to the human.
            Tool calls do not. Be concise — one paragraph or less per turn.
            Do not narrate "I will now assign a task" — just assign it. After
            units report, summarize the outcome briefly.

            Termination: emit a final text reply when the user's request is
            handled OR when you need more input from them. Don't keep dispatching
            forever — each user prompt should resolve.

            Examples:
              user: 去挖10块铁
              → assign_task(1, "Find and mine 10 iron_ore within 64 blocks of
                              the player's position. Report exact count and
                              your final coordinates.")
              (no preamble, just dispatch)

              user: 那个僵尸在哪
              → scan_nearby_entities(radius=24, type_filter="hostile")
              → "西边 12 格,id=42"

              <task_result state="completed" unit_id=1> Mined 10 iron_ore...
              → "搞定 ✓ 10 块铁已收"
            """;

    private final ConvoState convo = new ConvoState();
    private final Map<Integer, String> pendingPrompts = new HashMap<>();

    private boolean awaitingLlmResponse = false;
    private boolean aborted = false;

    PlayerAgentLoop() {}

    public ConvoState convo() { return convo; }

    /** Player typed `/animus <prompt>` (or future GUI input). */
    public void submitPrompt(String text) {
        boolean wasAborted = aborted;
        aborted = false;
        convo.addUser(text);
        Constants.LOG.info("[animus-player] user prompt ({} chars){}: {}",
                text.length(), wasAborted ? " — reset abort" : "", truncate(text, 200));
        tryStartTurn();
    }

    /** Stash a prompt for a unit that's being summoned. */
    public void stashPendingPrompt(int unitId, String prompt) {
        pendingPrompts.put(unitId, prompt);
    }

    /** Pull (and remove) a stashed prompt — called by UnitSpawnedPayload handler. */
    public String drainPendingPrompt(int unitId) {
        return pendingPrompts.remove(unitId);
    }

    /**
     * Inject a sub-agent (EntityAgent) report as a synthetic
     * <strong>user-role</strong> message into PlayerAgent's conversation.
     * Mirrors opencode's {@code TaskTool.inject} background pattern
     * ({@code packages/opencode/src/tool/task.ts:246-269}) — the message
     * becomes a permanent part of conversation history, so a chat-tab
     * scrollback shows every unit's report in chronological order.
     *
     * <p>Format mirrors opencode's {@code backgroundMessage}:
     * <pre>
     * Background task completed: unit X
     * unit_id: X
     * state: completed
     *
     * &lt;task_result&gt;
     * {report body}
     * &lt;/task_result&gt;
     * </pre>
     * The {@code state} value distinguishes "completed" (clean final text)
     * from "error" / "aborted" / "died". The LLM can pattern-match on this
     * structured form without needing to parse free text.
     *
     * <p>{@link #notifyReportArrived} runs after the inject to trigger the
     * next turn if the agent is idle (opencode's {@code continueIfIdle}).
     */
    public void injectSubagentReport(int unitId, String state, String body) {
        String message = "Background task " + state + ": unit " + unitId + "\n"
                + "unit_id: " + unitId + "\n"
                + "state: " + state + "\n"
                + "\n"
                + "<task_result>\n"
                + (body == null ? "" : body) + "\n"
                + "</task_result>";
        convo.addUser(message);
        Constants.LOG.info("[animus-player] ← report unit={} state={} body={}",
                unitId, state, truncate(body, 200));
        notifyReportArrived();
    }

    /**
     * Triggers a new turn iff PlayerAgent is currently idle. Mirrors
     * opencode's {@code resumeWhenIdle} gate ({@code task.ts:214-244}) —
     * if the agent is mid-turn when a report lands, the message stays in
     * history and gets processed on the next natural turn boundary.
     */
    public void notifyReportArrived() {
        if (awaitingLlmResponse) return;
        if (!convo.snapshot().isEmpty()) tryStartTurn();
    }

    // ---- internals ----

    private void tryStartTurn() {
        if (aborted) return;
        if (awaitingLlmResponse) return;
        if (convo.snapshot().isEmpty()) return;
        if (convo.turnCount() >= ConvoState.MAX_TOOL_TURN_COUNT) {
            Constants.LOG.warn("[animus-player] turn cap reached, stopping");
            aborted = true;
            return;
        }
        if (!AnimusLlmClient.isConfigured()) {
            Constants.LOG.warn("[animus-player] API key not set; open the Animus GUI (X) to configure");
            aborted = true;
            return;
        }

        convo.incrementTurn();
        awaitingLlmResponse = true;

        var tools = ToolRegistry.forRole(AgentRole.PLAYER);
        var snapshot = convo.snapshot();
        IAnimusConfig config = Services.CONFIG;
        String systemPrompt = composeSystemPrompt(config.getSystemPrompt());

        Constants.LOG.info("[animus-player] turn {}: convo={} msgs, tools={}",
                convo.turnCount(), snapshot.size(), tools.size());

        AnimusLlmClient.instance().chatStreaming(snapshot, tools, systemPrompt, null)
                .whenComplete(this::bounceBackToMain);
    }

    private String composeSystemPrompt(String basePrompt) {
        String base = basePrompt == null ? "" : basePrompt;
        String envBlock = buildEnvBlock();
        String skillsXml = SkillRegistry.instance().formatXml();

        StringBuilder sb = new StringBuilder();
        if (!base.isBlank()) sb.append(base);
        sb.append(PLAYER_PROMPT);
        if (envBlock != null) {
            sb.append("\n\n").append(envBlock);
        }
        if (!skillsXml.isEmpty()) {
            sb.append("\n\n").append(skillsXml);
        }
        return sb.toString();
    }

    private String buildEnvBlock() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return null;

        StringBuilder sb = new StringBuilder("<env>\n");
        sb.append("  owner_name: ").append(player.getName().getString()).append('\n');
        sb.append("  today: ").append(LocalDate.now()).append('\n');
        sb.append("  dimension: ").append(player.level().dimension().identifier()).append('\n');
        sb.append("  owner_status: hp=").append(formatNum(player.getHealth()))
          .append('/').append(formatNum(player.getMaxHealth()))
          .append(", hunger=").append(player.getFoodData().getFoodLevel())
          .append("/20, pos=(").append(formatNum(player.getX()))
          .append(", ").append(formatNum(player.getY()))
          .append(", ").append(formatNum(player.getZ())).append(")\n");

        sb.append("  units:\n");
        ClientPlayerAnimusState state = ClientPlayerAnimusState.instance();
        for (int i = 1; i <= 6; i++) {
            ClientUnitView unit = state.unit(i);
            sb.append("    - id=").append(i);
            sb.append(", name=").append(unit.name() == null ? "null" : "\"" + unit.name() + "\"");
            sb.append(", model=").append(unit.modelKey());
            sb.append(", status=").append(unit.active() ? "working" : (unit.alive() ? "idle" : "dead"));
            sb.append(", alive=").append(unit.alive());
            sb.append('\n');
        }
        // Note: unit reports are NOT injected here — they now live as
        // synthetic user-role messages in conversation history (see
        // injectSubagentReport), mirroring opencode's background inject
        // pattern. env block holds only "current snapshot" facts.

        sb.append("</env>");
        return sb.toString();
    }

    private static String formatNum(double v) {
        return String.format("%.1f", v);
    }

    private void bounceBackToMain(AssistantTurn turn, Throwable err) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> handleResponse(turn, err));
    }

    private void handleResponse(AssistantTurn turn, Throwable err) {
        awaitingLlmResponse = false;

        if (err != null) {
            Constants.LOG.warn("[animus-player] LLM call failed: {}", unwrap(err));
            aborted = true;
            return;
        }
        if (turn == null) {
            Constants.LOG.warn("[animus-player] LLM returned null turn");
            aborted = true;
            return;
        }
        convo.addAssistant(turn);

        if (!turn.hasToolCalls()) {
            if (!turn.content().isEmpty()) {
                Constants.LOG.info("[animus-player] FINAL: {}", turn.content());
            }
            convo.resetTurnCount();
            return;
        }

        if (convo.recordToolBatchAndCheckLoop(turn.toolCalls())) {
            String summary = turn.toolCalls().stream()
                    .map(LlmToolCall::name).collect(Collectors.joining(","));
            Constants.LOG.warn("[animus-player] tool batch loop ({})", summary);
            for (LlmToolCall tc : turn.toolCalls()) {
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"aborted: tool batch loop\"}");
            }
            aborted = true;
            return;
        }

        // PlayerAgent has no world-action tools — every dispatch resolves locally.
        for (LlmToolCall tc : turn.toolCalls()) {
            AnimusTool tool = ToolRegistry.get(tc.name());
            if (tool == null || !tool.allowedRoles().contains(AgentRole.PLAYER)) {
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"unknown / wrong-role tool: " + escape(tc.name()) + "\"}");
                continue;
            }
            if (!tool.isLocal()) {
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"PlayerAgent does not run world-action tools; use assign_task\"}");
                continue;
            }
            String resultJson;
            try {
                JsonObject args = parseArgs(tc.arguments());
                ClientToolContext ctx = ClientToolContext.forPlayer(Minecraft.getInstance().player);
                resultJson = tool.executeLocal(args, ctx);
            } catch (RuntimeException ex) {
                resultJson = "{\"success\":false,\"message\":\"" + escape(ex.getMessage()) + "\"}";
            }
            convo.addToolResult(tc.id(), resultJson);
            Constants.LOG.info("[animus-player] local-exec tool={} args={} → {}",
                    tc.name(), truncate(tc.arguments(), 120), truncate(resultJson, 200));
        }

        // All PlayerAgent tools are local + synchronous → next turn immediately.
        tryStartTurn();
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
