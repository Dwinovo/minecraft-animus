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
import java.util.ArrayDeque;
import java.util.Deque;
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
 * <h2>Report queue</h2>
 * {@link #recentReports} accumulates final-text messages from EntityAgents.
 * Each turn's env block surfaces the latest {@link #MAX_REPORTS_DISPLAYED}.
 * When a report lands and PlayerAgent is idle, the loop auto-triggers a new
 * turn so the brain "thinks" about the report immediately. If PlayerAgent
 * is mid-turn, the report just sits in the queue — the gate at
 * {@link #tryStartTurn} prevents double-fire (mirrors opencode's
 * {@code resumeWhenIdle}, validated in the research turn).
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

    public static final int MAX_REPORTS_DISPLAYED = 8;
    public static final int MAX_REPORTS_KEPT = 32;

    private final ConvoState convo = new ConvoState();
    private final Deque<UnitReport> recentReports = new ArrayDeque<>();
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

    /** Called by EntityAgentLoop on natural termination or by Unit death notifications. */
    public void pushReport(int unitId, String text) {
        if (recentReports.size() >= MAX_REPORTS_KEPT) recentReports.pollFirst();
        recentReports.addLast(new UnitReport(unitId, text));
        Constants.LOG.info("[animus-player] ← report unit={} : {}", unitId, truncate(text, 200));
    }

    /**
     * Called after a report is pushed. Triggers a new turn iff PlayerAgent
     * is currently idle — mirrors opencode's {@code resumeWhenIdle} gate.
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
        sb.append("\n\nYou are the PlayerAgent — the strategic brain. You cannot perform world actions directly. ");
        sb.append("Use assign_task(unit_id, prompt) to dispatch work to one of your units (1-6). ");
        sb.append("Each unit runs independently in parallel and reports back via recent_reports. ");
        sb.append("Use the read-only perception tools to gather information before deciding.");
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

        if (!recentReports.isEmpty()) {
            sb.append("  recent_reports:\n");
            int shown = 0;
            // Iterate newest-last; show the last N
            int skip = Math.max(0, recentReports.size() - MAX_REPORTS_DISPLAYED);
            int idx = 0;
            for (UnitReport r : recentReports) {
                if (idx++ < skip) continue;
                sb.append("    - [unit ").append(r.unitId()).append("] ")
                  .append(escapeOneLine(r.text())).append('\n');
                shown++;
            }
            sb.append("  reports_total: ").append(recentReports.size())
              .append(", reports_shown: ").append(shown).append('\n');
        }

        sb.append("</env>");
        return sb.toString();
    }

    private static String formatNum(double v) {
        return String.format("%.1f", v);
    }

    private static String escapeOneLine(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ');
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

    /** Report record — bound to its unit_id, kept newest-last in the queue. */
    public record UnitReport(int unitId, String text) {}
}
