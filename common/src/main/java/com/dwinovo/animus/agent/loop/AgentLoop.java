package com.dwinovo.animus.agent.loop;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.agent.llm.AnimusLlmClient;
import com.dwinovo.animus.agent.llm.ConvoState;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ToolAdapter;
import com.dwinovo.animus.agent.tool.ToolRegistry;
import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.task.TaskRecord;
import com.google.gson.JsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-entity agent loop. The piece that turns {@code player → entity GUI →
 * LLM tool_calls → vanilla world execution → tool results → next LLM turn}
 * into a single state machine.
 *
 * <h2>Threading rules</h2>
 * Every mutation here runs on the server main thread. There's exactly one
 * cross-thread hop, in {@link #handleResponse}: the
 * {@code CompletableFuture.whenComplete} callback fires on the OpenAI SDK's
 * internal executor; we immediately bounce into {@code server.execute(...)}
 * and do all work after that on the tick thread. No locks needed.
 *
 * <h2>State variables</h2>
 * <ul>
 *   <li>{@code awaitingLlmResponse} — true between {@code chat().create()}
 *       and the bounced-back {@link #handleResponse}. While true, no new turn
 *       starts and tick() is a no-op for result drainage. Prevents the
 *       "double turn" race that would otherwise happen if user-prompt and
 *       tool-completion arrived in the same tick.</li>
 *   <li>{@code pendingToolCallIds} — the tool_call_ids we've enqueued
 *       (handed off to {@link AnimusEntity#getTaskQueue}) but haven't gotten
 *       results back for yet. When the set empties AND the LLM isn't running,
 *       we know to start the next turn.</li>
 *   <li>{@code aborted} — sticky flag set when the turn cap is hit, a loop
 *       is detected, or LLM not configured. Resets on next user prompt.</li>
 * </ul>
 *
 * <h2>Why per-entity instead of mod-global</h2>
 * Each Animus has its own conversation (the {@link ConvoState}) and its own
 * task queue. The loop coordinates between them, so it's also per-entity.
 * Mod-global would force coordination across unrelated entities.
 */
public final class AgentLoop {

    private final AnimusEntity entity;
    private final ConvoState convo = new ConvoState();
    private final Set<String> pendingToolCallIds = new HashSet<>();

    private boolean awaitingLlmResponse = false;
    private boolean aborted = false;

    public AgentLoop(AnimusEntity entity) {
        this.entity = entity;
    }

    public ConvoState convo() { return convo; }

    /** Called from the prompt-payload handler on the server thread. */
    public void submitPrompt(String text) {
        if (entity.isRemoved()) return;
        aborted = false;  // user reset
        convo.addUser(text);
        Constants.LOG.debug("[animus-agent#{}] user: {}", entity.getId(), text);
        tryStartTurn();
    }

    /**
     * Called every server tick from {@code AnimusEntity.customServerAiStep}.
     * Drains completed task results into the conversation and triggers the
     * next LLM turn when nothing is in-flight.
     */
    public void tick() {
        if (awaitingLlmResponse) return;
        if (entity.isRemoved()) return;
        List<TaskRecord> done = entity.getTaskQueue().drainCompleted();
        if (done.isEmpty()) return;
        for (TaskRecord rec : done) {
            String resultJson = rec.getResult() == null
                    ? "{\"success\":false,\"message\":\"no result produced\"}"
                    : rec.getResult().toJson();
            convo.addToolResult(rec.getToolCallId(), resultJson);
            pendingToolCallIds.remove(rec.getToolCallId());
            Constants.LOG.debug("[animus-agent#{}] tool {} -> {}",
                    entity.getId(), rec.getToolName(), resultJson);
        }
        if (pendingToolCallIds.isEmpty()) {
            tryStartTurn();
        }
    }

    /** Called when the entity is being removed. Cancels and flushes the chain. */
    public void shutdown() {
        entity.getTaskQueue().cancelAll("entity removed");
        // Don't bother feeding the cancelled results to the LLM — entity is going away.
        pendingToolCallIds.clear();
        awaitingLlmResponse = false;
        aborted = true;
    }

    // ---- internals ----

    private void tryStartTurn() {
        if (aborted) return;
        if (awaitingLlmResponse) return;
        if (!pendingToolCallIds.isEmpty()) return;
        if (convo.snapshot().isEmpty()) return;  // nothing to ask about
        if (convo.turnCount() >= ConvoState.MAX_TOOL_TURN_COUNT) {
            Constants.LOG.warn("[animus-agent#{}] turn cap ({}) reached, stopping",
                    entity.getId(), ConvoState.MAX_TOOL_TURN_COUNT);
            aborted = true;
            return;
        }
        if (!AnimusLlmClient.isConfigured()) {
            Constants.LOG.warn("[animus-agent#{}] OpenAI api_key not set; configure mod first",
                    entity.getId());
            aborted = true;
            return;
        }

        convo.incrementTurn();
        awaitingLlmResponse = true;

        List<ChatCompletionTool> openaiTools = ToolAdapter.toOpenAITools(ToolRegistry.all());
        List<ConvoState.Msg> snapshot = convo.snapshot();
        String systemPrompt = Services.CONFIG.getSystemPrompt();

        Constants.LOG.debug("[animus-agent#{}] LLM call turn={} msgs={} tools={}",
                entity.getId(), convo.turnCount(), snapshot.size(), openaiTools.size());

        AnimusLlmClient.instance().chat(snapshot, openaiTools, systemPrompt)
                .whenComplete((resp, err) -> bounceBackToTick(resp, err));
    }

    private void bounceBackToTick(ChatCompletion resp, Throwable err) {
        if (entity.level().isClientSide()) return;
        MinecraftServer server = entity.level().getServer();
        if (server == null) return;
        server.execute(() -> handleResponse(resp, err));
    }

    private void handleResponse(ChatCompletion resp, Throwable err) {
        awaitingLlmResponse = false;
        if (entity.isRemoved()) return;

        if (err != null) {
            Constants.LOG.warn("[animus-agent#{}] LLM call failed: {}",
                    entity.getId(), unwrap(err));
            aborted = true;
            return;
        }
        if (resp == null || resp.choices().isEmpty()) {
            Constants.LOG.warn("[animus-agent#{}] LLM returned no choices", entity.getId());
            aborted = true;
            return;
        }

        ChatCompletionMessage msg = resp.choices().get(0).message();
        convo.addAssistant(msg);
        resp.usage().ifPresent(u -> Constants.LOG.debug(
                "[animus-agent#{}] usage: in={} out={} total={}",
                entity.getId(), u.promptTokens(), u.completionTokens(), u.totalTokens()));

        // Plain text final?
        if (msg.toolCalls().isEmpty() || msg.toolCalls().get().isEmpty()) {
            msg.content().ifPresent(text -> Constants.LOG.info(
                    "[animus-agent#{}] assistant: {}", entity.getId(), text));
            // End of chain — next user prompt starts fresh.
            convo.resetTurnCount();
            return;
        }

        List<ChatCompletionMessageToolCall> toolCalls = msg.toolCalls().get();
        List<String> names = new ArrayList<>(toolCalls.size());
        for (ChatCompletionMessageToolCall tc : toolCalls) {
            if (tc.isFunction()) names.add(tc.asFunction().function().name());
            else names.add("<unsupported>");
        }
        if (convo.recordToolBatchAndCheckLoop(names)) {
            Constants.LOG.warn("[animus-agent#{}] tool batch loop detected ({}); stopping",
                    entity.getId(), String.join(",", names));
            // Push a synthetic tool result for each id so the conversation stays valid,
            // then mark aborted so no further LLM call happens this chain.
            for (ChatCompletionMessageToolCall tc : toolCalls) {
                if (!tc.isFunction()) continue;
                convo.addToolResult(tc.asFunction().id(),
                        "{\"success\":false,\"message\":\"aborted: tool batch loop detected\"}");
            }
            aborted = true;
            return;
        }

        long now = entity.level().getGameTime();
        for (ChatCompletionMessageToolCall tc : toolCalls) {
            if (!tc.isFunction()) {
                Constants.LOG.warn("[animus-agent#{}] unsupported tool_call variant; skipping",
                        entity.getId());
                continue;
            }
            var fn = tc.asFunction();
            String id = fn.id();
            String name = fn.function().name();
            String argsRaw = fn.function().arguments();

            AnimusTool tool = ToolRegistry.get(name);
            if (tool == null) {
                convo.addToolResult(id,
                        "{\"success\":false,\"message\":\"unknown tool: " + name + "\"}");
                continue;
            }
            try {
                JsonObject args = ToolAdapter.parseArguments(argsRaw);
                TaskRecord rec = tool.toTaskRecord(id, args, now);
                pendingToolCallIds.add(id);
                entity.getTaskQueue().enqueue(rec);
                Constants.LOG.debug("[animus-agent#{}] enqueue tool={} id={} args={}",
                        entity.getId(), name, id, argsRaw);
            } catch (RuntimeException ex) {
                convo.addToolResult(id,
                        "{\"success\":false,\"message\":\"invalid arguments: "
                                + escape(ex.getMessage()) + "\"}");
            }
        }

        // If every tool_call failed at parse time (or was unsupported), nothing
        // landed in the queue — kick the next turn immediately so the LLM sees
        // the synthetic failure results.
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
