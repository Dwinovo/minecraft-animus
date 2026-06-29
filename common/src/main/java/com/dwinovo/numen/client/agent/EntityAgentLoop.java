package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ClientToolContext;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.network.payload.CancelTasksPayload;
import com.dwinovo.numen.network.payload.ExecuteToolPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-entity agent loop running on the <strong>client</strong>. One instance
 * per Numen the player talks to, keyed by the stable {@code entity.getUUID()}
 * in {@link AgentLoopRegistry} and resolved to the current body via
 * {@link ClientNumenLookup} (so it survives the int-id churn of dimension
 * travel). The agent is bound to that one entity for its
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
 *   <li>LLM response — {@link NumenLlmClient#chatStreaming} resolves on the
 *       HTTP executor thread; {@link #bounceBackToMain} hops via
 *       {@code Minecraft.getInstance().execute} before any mutation</li>
 * </ul>
 */
public final class EntityAgentLoop {

    /**
     * Persona prompt for a single Numen body. Deliberately does NOT enumerate
     * tools — the live tool list (with full descriptions) rides along on every
     * request, and a prose copy here rotted badly once already. This prompt
     * carries only what the tool schemas can't: identity, working discipline,
     * and the voice toward the owner.
     */
    private static final String ENTITY_PROMPT = com.dwinovo.numen.agent.prompt.NumenPrompts.ENTITY_PROMPT;

    // ---- context compaction (mirrors Claude Code's /compact machinery) ----

    /**
     * The model context window now comes per-model from {@code ModelRegistry} (numen_models.json),
     * looked up from the configured provider+model at the auto-compaction gate; unknown/custom models
     * fall back to {@code ModelRegistry.DEFAULT_CTX} (64k).
     */
    /**
     * Headroom under the window at which auto-compaction fires (Claude Code's
     * {@code AUTOCOMPACT_BUFFER_TOKENS}): the next turn adds tool results and
     * a fresh system prompt on top of the last measured request, and the
     * summarization call itself must still fit.
     */
    private static final int AUTO_COMPACT_BUFFER_TOKENS = 13_000;
    /** Don't bother summarizing a conversation shorter than this. */
    private static final int MIN_COMPACT_MESSAGES = 8;
    /** Circuit breaker: stop auto-retrying after this many consecutive failures. */
    private static final int MAX_COMPACT_FAILURES = 3;

    private static final String COMPACT_SYSTEM_PROMPT =
            "You are a helpful AI assistant tasked with summarizing conversations "
            + "between a Minecraft companion entity (the Numen) and its owner.";

    /**
     * The summarization request, appended as the final user message over the
     * full history. Adapted from Claude Code's compact prompt to what a
     * Minecraft body must never forget: coordinates, inventory, lessons.
     */
    private static final String COMPACT_PROMPT = """
            请将以上整段对话压缩成一份详细摘要。这份摘要将完全替代之前的对话历史，\
            成为你后续行动的唯一记忆——任何没写进摘要的信息都会永久丢失，所以请把还会用到的信息全部保留。

            按以下结构输出：
            1. 主人的指令与意图：所有明确的请求，以及当前正在执行哪一个。
            2. 世界知识：所有提到过的重要坐标（基地、传送门、熔炉、工作台、矿点、要塞等）、维度和地标。坐标数字必须逐字保留。
            3. 自身状态：最近已知的 HP、装备、背包中的关键物品及数量。
            4. 已完成的事项：按时间顺序简述。
            5. 失败与教训：失败过的操作、原因、以及学到的约束（例如某处有岩浆、某条路线不可达、某方块需要特定工具）。
            6. 待办任务：计划中尚未完成的事项及其状态。
            7. 当前工作与下一步：摘要请求前正在做什么，接下来的第一步是什么。

            只输出摘要本身。不要调用工具，不要添加摘要以外的评论。""";

    /** Wrapper that turns the raw summary into the new history's first user message. */
    private static final String SUMMARY_HEADER =
            "[对话历史已压缩] 以下是此前全部对话的摘要，请将其作为既成事实继续工作：\n\n";

    private final UUID entityUuid;
    /** JSONL persistence under {@code config/numen/conversations/<uuid>.jsonl}. */
    private final ConvoLog log;
    private final ConvoState convo;
    /** Functional-block coordinate memory, injected as {@code <known_blocks>}. */
    private final WorkBlockMemory workBlocks;
    /** Outstanding world-action calls: tool_call id → tool name (the name drives
     *  result harvesting in {@link #harvestWorkBlocks}). */
    private final Map<String, String> pendingToolCalls = new HashMap<>();

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
     * The current turn's tool calls awaiting dispatch, drained strictly one at a
     * time: the next is dispatched only when the current one's result lands.
     * This is the agent's single synchronous tool queue — one body, one brain,
     * one thing at a time. No async, no concurrency (by design, for now).
     */
    private final Deque<LlmToolCall> turnQueue = new ArrayDeque<>();

    /**
     * Reentrancy guard for {@link #dispatchNext}: a client-side tool that
     * finishes synchronously calls back into {@link #complete} mid-dispatch;
     * the guard lets the dispatch loop keep draining the queue instead of
     * recursing into itself.
     */
    private boolean advancing = false;

    /**
     * Wall-clock deadline (epoch millis) for the single in-flight tool call, or
     * {@code 0} when nothing is in flight. This is a <em>backstop</em> for a
     * dead-server / never-replying tool only — deliberately generous, so a core
     * tool (which always gets a real server reply, even on death/removal) never
     * trips it. Tight per-tool timeouts arrive with the extension API.
     */
    private long inFlightDeadlineMillis = 0;
    private static final long TOOL_BACKSTOP_MILLIS = 15 * 60 * 1000L;

    /** A summarization call is in flight; blocks normal turns until it lands. */
    private boolean compacting = false;
    /** Context size of the last request as the API counted it (0 = unknown yet). */
    private int lastPromptTokens = 0;
    /** Consecutive compaction failures — circuit breaker for the auto path. */
    private int compactFailures = 0;

    /**
     * Set while the body is DEAD and awaiting its timed respawn (see {@link #onEntityDied} /
     * {@link #onRespawned}). The loop is frozen — no LLM turn starts — until the body comes back.
     */
    private boolean dead = false;

    /** Death cause recorded at death, replayed in the respawn event (null while alive). */
    private String deathCause;
    /** Tool calls that were in flight when the body died — resolved on respawn, not before. */
    private List<String> deathInterruptedCalls = List.of();

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

    EntityAgentLoop(UUID entityUuid) {
        this.entityUuid = entityUuid;
        Path numenRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("numen");
        this.log = ConvoLog.forEntity(numenRoot.resolve("conversations"), entityUuid);
        this.convo = new ConvoState(log::append);
        this.workBlocks = WorkBlockMemory.forEntity(numenRoot.resolve("memory"), entityUuid);
        restoreFromDisk();
    }

    /**
     * Replay the persisted conversation tail into memory and heal whatever a
     * dead session left dangling, so the first request after a relaunch is
     * protocol-valid:
     * <ul>
     *   <li>assistant tool_calls whose results never arrived (the game closed
     *       mid-task) get synthetic "interrupted" results — the same trick as
     *       the owner's Stop button; the synthetic results also append to the
     *       file, healing it on disk;</li>
     *   <li>a trailing user message (closed while waiting on the LLM) is
     *       capped with a short assistant note, mirroring {@link #abort}, so
     *       the next prompt doesn't create back-to-back user messages.</li>
     * </ul>
     */
    private void restoreFromDisk() {
        List<ConvoState.Msg> history = log.load(ConvoLog.DEFAULT_LOAD_LIMIT);
        if (history.isEmpty()) return;
        convo.preload(history);

        List<String> dangling = ConvoLog.unansweredToolCallIds(history);
        for (String id : dangling) {
            convo.addToolResult(id,
                    "{\"success\":false,\"message\":\"interrupted: the game was closed before this finished\"}");
        }
        if (convo.lastMessage() instanceof ConvoState.Msg.User) {
            convo.addAssistant(new AssistantTurn("(已中断)", List.of(), null));
        }
        Constants.LOG.info("[numen-entity#{}] restored {} msg(s) from disk{}",
                entityUuid, history.size(),
                dangling.isEmpty() ? "" : " (healed " + dangling.size() + " dangling tool call(s))");
    }

    public UUID entityUuid() { return entityUuid; }
    public ConvoState convo() { return convo; }

    /** Owner typed a prompt in the chat GUI. */
    public void submitPrompt(String text) {
        if (dead) {
            Constants.LOG.info("[numen-entity#{}] prompt ignored — body is dead", entityUuid);
            return;
        }
        boolean wasAborted = aborted;
        aborted = false;
        // Always buffer first; tryStartTurn() splices buffered prompts into the
        // conversation only at a protocol-valid point. If we're mid-turn (the
        // guards in tryStartTurn fire), the prompt stays buffered and gets
        // flushed once the outstanding assistant/tool round-trip completes —
        // this avoids inserting a user message between assistant(tool_calls)
        // and its tool results (which the API rejects with HTTP 400).
        boolean deferred = awaitingLlmResponse || !pendingToolCalls.isEmpty();
        bufferedPrompts.add(text);
        Constants.LOG.info("[numen-entity#{}] user prompt ({} chars){}{}: {}",
                entityUuid, text.length(),
                wasAborted ? " — reset previous abort" : "",
                deferred ? " — buffered (mid-turn)" : "",
                truncate(text, 200));
        tryStartTurn();
    }

    /** Server body reported a result for one of our outstanding calls (async path). */
    public void onToolResult(String toolCallId, String resultJson) {
        String toolName = pendingToolCalls.get(toolCallId);
        if (toolName == null) {
            Constants.LOG.debug("[numen-entity#{}] late tool_result id={} ignored",
                    entityUuid, toolCallId);
            return;
        }
        complete(toolCallId, toolName, resultJson);
    }

    /**
     * Land a tool result — the single completion path for every tool, whether
     * it ran synchronously on the client or came back from the server body.
     * Removes the in-flight call, harvests any work-block coordinates, writes
     * the {@code role:tool} message, then advances the serial queue (dispatch
     * the next call, or start the next LLM turn if the queue is now empty).
     */
    private void complete(String toolCallId, String toolName, String resultJson) {
        if (pendingToolCalls.remove(toolCallId) == null) {
            // Already settled by abort()/death/timeout, or a duplicate/late reply — drop it.
            return;
        }
        inFlightDeadlineMillis = 0;
        harvestWorkBlocks(toolName, resultJson);
        convo.addToolResult(toolCallId, resultJson);
        Constants.LOG.info("[numen-entity#{}] tool_result id={} tool={} (queued={}) → {}",
                entityUuid, toolCallId, toolName, turnQueue.size(),
                truncate(resultJson, 200));
        // Advance the serial queue. While dispatchNext is already draining
        // (advancing), skip — that loop picks up the next call itself.
        if (!advancing) dispatchNext();
    }

    /**
     * Drain the serial tool-call queue: dispatch the next call, or — when the
     * queue is empty and nothing is in flight — start the next LLM turn. Exactly
     * one call occupies the in-flight slot at a time; {@link #complete} calls
     * back here to advance after each result lands. A client-side tool that
     * completes synchronously inside {@code invoke} clears the slot mid-loop, so
     * the {@link #advancing} guard keeps this draining iteratively instead of
     * recursing.
     */
    private void dispatchNext() {
        if (advancing) return;
        advancing = true;
        try {
            while (pendingToolCalls.isEmpty()) {
                LlmToolCall tc = turnQueue.poll();
                if (tc == null) {
                    // Every tool call this turn has settled → next LLM turn.
                    tryStartTurn();
                    return;
                }
                NumenTool tool = ToolRegistry.resolve(tc.name());
                if (tool == null) {
                    Constants.LOG.warn("[numen-entity#{}] LLM called unknown tool '{}' (id={})",
                            entityUuid, tc.name(), tc.id());
                    convo.addToolResult(tc.id(),
                            TaskResult.fail("unknown tool: " + tc.name()).toJson());
                    continue;   // nothing in flight — drain the next queued call
                }
                final String id = tc.id();
                final String name = tc.name();
                final String rawArgs = tc.arguments();
                pendingToolCalls.put(id, name);
                inFlightDeadlineMillis = System.currentTimeMillis() + TOOL_BACKSTOP_MILLIS;
                ToolCall call = new ToolCall(id, name, rawArgs,
                        new ClientToolContext(resolveEntity(), entityUuid),
                        resultJson -> complete(id, name, resultJson),
                        () -> Services.NETWORK.sendToServer(
                                new ExecuteToolPayload(entityUuid, id, name, rawArgs)));
                Constants.LOG.info("[numen-entity#{}] dispatch tool={} id={} args={}",
                        entityUuid, name, id, truncate(rawArgs, 200));
                try {
                    tool.invoke(call);
                } catch (RuntimeException ex) {
                    Constants.LOG.warn("[numen-entity#{}] tool {} threw (id={}): {}",
                            entityUuid, name, id, ex.getMessage());
                    complete(id, name, TaskResult.fail(ex.getMessage()).toJson());
                }
                // Client-side tool: complete() already cleared the slot → loop
                // drains the next. Server tool: slot occupied → exit and wait for
                // onToolResult to advance us.
            }
        } finally {
            advancing = false;
        }
    }

    /**
     * Driven once per client tick (see {@code AgentLoopRegistry.tickAll}).
     * Enforces the in-flight backstop so a never-replying tool can't wedge the
     * synchronous loop forever; normal core tools reply long before this fires.
     */
    public void clientTick() {
        if (inFlightDeadlineMillis == 0 || pendingToolCalls.isEmpty()) return;
        if (System.currentTimeMillis() < inFlightDeadlineMillis) return;
        Map.Entry<String, String> inFlight = pendingToolCalls.entrySet().iterator().next();
        Constants.LOG.warn("[numen-entity#{}] tool {} id={} hit backstop timeout — failing it",
                entityUuid, inFlight.getValue(), inFlight.getKey());
        complete(inFlight.getKey(), inFlight.getValue(),
                TaskResult.fail("tool timed out (no result returned)").toJson());
    }

    /**
     * Pull functional-block coordinates out of successful tool results into
     * {@link WorkBlockMemory}. The results already carry them — place_block
     * reports the block it placed, interact_at reports the station it activated
     * (a chest/furnace/table it opened) — this just stops the loop from
     * forgetting them once the result scrolls out of context. Both tools report
     * the same {@code block} + {@code x/y/z} shape; {@code workBlocks.record}
     * filters to tracked station types, so non-station interactions fall away.
     */
    private void harvestWorkBlocks(String toolName, String resultJson) {
        try {
            JsonObject root = JsonParser.parseString(resultJson).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) return;
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : null;
            if (data == null) return;

            switch (toolName) {
                case "place_block", "interact_at" -> {
                    if (data.has("block") && data.has("x")) {
                        String path = data.get("block").getAsString();
                        int colon = path.indexOf(':');
                        if (colon >= 0) path = path.substring(colon + 1);
                        workBlocks.record(path, new net.minecraft.core.BlockPos(
                                data.get("x").getAsInt(),
                                data.get("y").getAsInt(),
                                data.get("z").getAsInt()));
                    }
                }
                default -> { /* nothing to harvest */ }
            }
        } catch (RuntimeException ex) {
            Constants.LOG.debug("[numen-entity#{}] work-block harvest skipped: {}",
                    entityUuid, ex.toString());
        }
    }

    // ---- interrupt (owner-triggered, from the chat GUI "Stop" button) ----

    /** A turn is actively running: waiting on the LLM, on tool results, or compacting. */
    public boolean isBusy() {
        return awaitingLlmResponse || compacting || !pendingToolCalls.isEmpty();
    }

    /** A summarization call is currently in flight (drives the GUI status line). */
    public boolean isCompacting() {
        return compacting;
    }

    /** Manual compaction is actionable right now (drives the Compact button). */
    public boolean canCompact() {
        return !dead && !isBusy() && convo.snapshot().size() >= MIN_COMPACT_MESSAGES;
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
     *       results and the next request stays protocol-valid. A
     *       {@code CancelTasksPayload} also ships to the server so the
     *       <em>body</em> stops too — without it the entity keeps walking/mining
     *       to its task deadline while only the conversation halts. Queued
     *       prompts are <em>preserved</em> — they flush on the next submit,
     *       exactly like Claude Code keeps its message queue across an
     *       interrupt.</li>
     *   <li><b>Idle but prompts are queued</b> (e.g. typed during a turn that was
     *       just interrupted and is now held) → drop the queue. Mirrors
     *       {@code popCommandFromQueue} when there's no running task to cancel.</li>
     * </ol>
     *
     * No-op when nothing is running and nothing is queued.
     */
    public void abort() {
        if (isBusy()) {
            // Priority 1: stop the running turn (or the in-flight compaction —
            // its response is generation-stamped too, so it gets discarded).
            turnGeneration++; // any in-flight LLM response is now stale → discarded on arrival
            boolean wasAwaitingLlm = awaitingLlmResponse;
            int cancelledTools = pendingToolCalls.size();
            awaitingLlmResponse = false;
            compacting = false;

            // Synthesize cancelled results for outstanding world-action calls so the
            // assistant(tool_calls) message keeps matching tool results. Real results
            // arriving later are dropped as "late" by onToolResult (id already removed).
            for (String id : pendingToolCalls.keySet()) {
                convo.addToolResult(id,
                        "{\"success\":false,\"message\":\"interrupted by owner\"}");
            }
            pendingToolCalls.clear();
            // The turn is cancelled — but its assistant(tool_calls) message lists
            // EVERY call, including the ones still queued (never dispatched under
            // serial). They need synthetic results too, or the next request is
            // protocol-invalid (tool_calls without matching results → HTTP 400).
            for (LlmToolCall tc : turnQueue) {
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"interrupted by owner\"}");
            }
            turnQueue.clear();
            inFlightDeadlineMillis = 0;

            // Stop the BODY, not just the conversation: tell the server to cancel
            // the running task and the queue. Harmless no-op when only an LLM
            // call (no world action) was in flight.
            Services.NETWORK.sendToServer(new CancelTasksPayload(entityUuid));

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
            Constants.LOG.info("[numen-entity#{}] interrupted by owner (awaitingLlm={}, cancelledTools={}, queued={})",
                    entityUuid, wasAwaitingLlm, cancelledTools, bufferedPrompts.size());
        } else if (!bufferedPrompts.isEmpty()) {
            // Priority 2: idle — drop the held queue.
            int dropped = bufferedPrompts.size();
            bufferedPrompts.clear();
            Constants.LOG.info("[numen-entity#{}] interrupt cleared {} queued prompt(s)",
                    entityUuid, dropped);
        }
    }

    /**
     * The body died — the server tells us via {@code NumenDeathPayload} with the death cause. SUSPEND
     * (not dispose): the companion respawns at its owner shortly and {@link #onRespawned} resumes us.
     * Discard any in-flight LLM turn (bump {@link #turnGeneration}), then heal the conversation so it
     * stays protocol-valid AND the brain learns why it stopped — resolve every in-flight tool call with
     * the death cause, and cap a trailing user message (mirrors {@link #restoreFromDisk}). Latch
     * {@link #dead} so no turn starts until respawn.
     */
    public void onEntityDied(String cause) {
        // FREEZE hard: stop all LLM output/work and feed the model NOTHING now (adding a tool result
        // here would let the loop continue). Just record what was in flight + the cause; everything is
        // restored on respawn. The body is gone, so its tool results will never arrive — we'll synth
        // them at respawn instead.
        deathCause = cause;
        // Resolve at respawn: the in-flight call PLUS every still-queued call of
        // this turn (all are listed in the assistant message, all need results).
        deathInterruptedCalls = new java.util.ArrayList<>(pendingToolCalls.keySet());
        for (LlmToolCall tc : turnQueue) deathInterruptedCalls.add(tc.id());
        turnGeneration++;          // discard any in-flight LLM response (halt output)
        awaitingLlmResponse = false;
        compacting = false;
        pendingToolCalls.clear();
        turnQueue.clear();
        inFlightDeadlineMillis = 0;
        bufferedPrompts.clear();
        dead = true;
        Constants.LOG.info("[numen-entity#{}] body died ({}) — loop frozen ({} call(s) in flight)",
                entityUuid, cause, deathInterruptedCalls.size());
    }

    /**
     * The body respawned at its owner after dying — thaw the frozen loop and ONLY NOW restore context:
     * resolve any tool call that was interrupted by the death (so the conversation is valid and the
     * brain learns its task was cut short), then inject a {@code <event>} detailing the death cause.
     * Nothing was fed to the model while dead, so it stayed fully stopped for the whole timer.
     */
    public void onRespawned(String payloadCause) {
        boolean wasFrozen = dead;                 // same-session death (mid-task) vs a fresh loop after relog
        dead = false;
        if (wasFrozen) {
            for (String id : deathInterruptedCalls) {
                convo.addToolResult(id, TaskResult.fail("任务因你死亡而中断").toJson());
            }
            deathInterruptedCalls = List.of();
            if (convo.lastMessage() instanceof ConvoState.Msg.User) {
                convo.addAssistant(new AssistantTurn("(已中断)", List.of(), null));
            }
        }
        // Prefer the cause carried by the respawn payload (survives a logout that cleared deathCause).
        String raw = (payloadCause != null && !payloadCause.isBlank()) ? payloadCause
                : (deathCause != null ? deathCause : "未知原因");
        String cause = raw.replace('<', '(').replace('>', ')');
        deathCause = null;
        Constants.LOG.info("[numen-entity#{}] respawned ({}) — loop thawed", entityUuid, cause);
        // urgent only when it died mid-task (react now); a fresh post-login revival waits for the owner.
        injectEvent("<event kind=\"death\">你刚才死了(" + cause
                + "),物品掉落在死亡地点,手头的任务中断了;现已在主人身边复活。先看看状况,继续或重新规划。</event>", wasFrozen);
    }

    /**
     * Inject an asynchronous world event into the conversation (dimension change, hazard, …) — the
     * generic version of the Claude-Code "channel notification": the event rides the same buffered
     * queue as owner prompts, so it splices in only at a protocol-valid boundary. {@code urgent} wakes
     * an idle brain to react now; otherwise it sits in the queue and the brain sees it on the next
     * owner-driven turn (no extra LLM call, no unprompted chatter). Dropped while frozen by death.
     */
    public void injectEvent(String xml, boolean urgent) {
        if (dead) return;
        bufferedPrompts.add(xml);
        Constants.LOG.info("[numen-entity#{}] event queued{}: {}",
                entityUuid, urgent ? " (urgent)" : "", truncate(xml, 120));
        if (urgent) {
            tryStartTurn();
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
        // counter (just log numbering now that the hard cap is gone).
        convo.resetTurnCount();
    }

    private void tryStartTurn() {
        if (dead) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: body dead", entityUuid);
            return;
        }
        if (aborted) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: aborted", entityUuid);
            return;
        }
        if (awaitingLlmResponse) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: awaitingLlmResponse", entityUuid);
            return;
        }
        if (compacting) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: compacting", entityUuid);
            return;
        }
        if (!pendingToolCalls.isEmpty()) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: {} tool result(s) pending",
                    entityUuid, pendingToolCalls.size());
            return;
        }
        // Safe point: no assistant reply in flight and no tool results
        // outstanding, so the conversation ends with either a tool result or a
        // final assistant message — a user message can now be appended legally.
        flushBufferedPrompts();
        if (convo.snapshot().isEmpty()) return;
        // No hard cap on tool-call turns and no loop guard — a capable agent
        // legitimately chains many tasks, and resuming a timed-out move_to
        // repeats the exact same call. Runaways are stopped by the owner's
        // interrupt.
        if (!NumenLlmClient.isConfigured()) {
            Constants.LOG.warn("[numen-entity#{}] API key not set; open the Numen GUI (X) → Settings",
                    entityUuid);
            aborted = true;
            return;
        }

        // Auto-compaction gate: the last request's true context size (as the
        // API counted it) is within the buffer of the window — summarize FIRST,
        // then this method re-runs and dispatches the turn on the compacted
        // history. Mirrors Claude Code's autoCompactIfNeeded.
        int window = com.dwinovo.numen.agent.model.ModelRegistry.contextWindow(
                com.dwinovo.numen.client.screen.LlmProviders.normalize(com.dwinovo.numen.platform.Services.CONFIG.getProvider()),
                com.dwinovo.numen.platform.Services.CONFIG.getModel());
        if (lastPromptTokens >= window - AUTO_COMPACT_BUFFER_TOKENS
                && convo.snapshot().size() >= MIN_COMPACT_MESSAGES
                && compactFailures < MAX_COMPACT_FAILURES) {
            Constants.LOG.info("[numen-entity#{}] auto-compacting: last prompt {} tokens >= {} - {}",
                    entityUuid, lastPromptTokens, window, AUTO_COMPACT_BUFFER_TOKENS);
            startCompaction(true);
            return;
        }

        convo.incrementTurn();
        awaitingLlmResponse = true;

        var tools = ToolRegistry.all();
        var snapshot = convo.snapshot();
        INumenConfig config = Services.CONFIG;
        String systemPrompt = composeSystemPrompt(config.getSystemPrompt());

        Constants.LOG.info("[numen-entity#{}] turn {}: convo={} msgs, tools={}",
                entityUuid, convo.turnCount(), snapshot.size(), tools.size());

        // Capture the current generation; if the owner interrupts before this
        // call resolves, handleResponse sees the mismatch and discards it.
        final int gen = turnGeneration;
        NumenLlmClient.instance().chatStreaming(snapshot, tools, systemPrompt, null)
                .whenComplete((res, err) -> bounceBackToMain(gen, res, err));
    }

    // ---- compaction ----

    /**
     * Owner pressed the GUI's Compact button. Runs the same machinery as the
     * automatic path; silently ignored when a turn is in flight or there is
     * too little history to be worth a summarization call.
     */
    public void requestCompact() {
        if (!canCompact()) {
            Constants.LOG.info("[numen-entity#{}] manual compact ignored (busy={}, msgs={})",
                    entityUuid, isBusy(), convo.snapshot().size());
            return;
        }
        if (!NumenLlmClient.isConfigured()) return;
        startCompaction(false);
    }

    /**
     * Fire the summarization call: full history + the compact prompt as the
     * final user message, NO tools, a minimal system prompt (skills XML and the
     * persona would only waste the very tokens we're trying to reclaim).
     */
    private void startCompaction(boolean auto) {
        compacting = true;
        List<ConvoState.Msg> request = new ArrayList<>(convo.snapshot());
        request.add(new ConvoState.Msg.User(COMPACT_PROMPT));
        Constants.LOG.info("[numen-entity#{}] compaction started ({}, {} msgs)",
                entityUuid, auto ? "auto" : "manual", request.size() - 1);
        final int gen = turnGeneration;
        NumenLlmClient.instance().chatStreaming(request, List.of(), COMPACT_SYSTEM_PROMPT, null)
                .whenComplete((res, err) -> Minecraft.getInstance().execute(
                        () -> finishCompaction(gen, auto, res, err)));
    }

    private void finishCompaction(int gen, boolean auto,
                                  NumenLlmClient.ChatResult res, Throwable err) {
        if (gen != turnGeneration) {
            Constants.LOG.info("[numen-entity#{}] discarding interrupted compaction (gen {} != {})",
                    entityUuid, gen, turnGeneration);
            return;   // abort() already reset the compacting flag
        }
        compacting = false;

        String summary = (err == null && res != null) ? res.turn().content() : null;
        if (summary == null || summary.isBlank()) {
            compactFailures++;
            Constants.LOG.warn("[numen-entity#{}] compaction failed ({}/{}): {}",
                    entityUuid, compactFailures, MAX_COMPACT_FAILURES,
                    err != null ? unwrap(err) : "empty summary");
            // The conversation is untouched — the next turn just runs uncompacted.
            if (auto || !bufferedPrompts.isEmpty()) tryStartTurn();
            return;
        }

        String wrapped = SUMMARY_HEADER + summary.strip();
        // Boundary into the JSONL first (relaunches replay the compacted view;
        // the raw pre-compaction history stays in the file as an archive), then
        // swap the in-memory history without re-notifying the sink.
        log.appendCompactSummary(wrapped);
        convo.replaceAll(List.of(new ConvoState.Msg.User(wrapped)));
        lastPromptTokens = 0;   // unknown until the next request reports usage
        compactFailures = 0;
        Constants.LOG.info("[numen-entity#{}] compaction done ({}): history → 1 summary msg ({} chars)",
                entityUuid, auto ? "auto" : "manual", wrapped.length());

        // Auto-compaction interrupted a turn that was about to dispatch —
        // resume it so the task chain continues on the compacted history. After
        // a MANUAL compact we stay idle unless prompts queued up meanwhile.
        if (auto || !bufferedPrompts.isEmpty()) tryStartTurn();
    }

    private String composeSystemPrompt(String basePrompt) {
        String base = basePrompt == null ? "" : basePrompt;
        String envBlock = buildEnvBlock();
        AbstractClientPlayer body = resolveEntity();
        String knownBlocks = workBlocks.formatXml(body != null ? body.level() : null);
        String skillsXml = SkillRegistry.instance().formatXml();

        StringBuilder sb = new StringBuilder();
        if (!base.isBlank()) sb.append(base);
        sb.append(ENTITY_PROMPT);
        if (envBlock != null) {
            sb.append("\n\n").append(envBlock);
        }
        if (!knownBlocks.isEmpty()) {
            sb.append("\n\n").append(knownBlocks);
        }
        if (!skillsXml.isEmpty()) {
            sb.append("\n\n").append(skillsXml);
        }
        return sb.toString();
    }

    private String buildEnvBlock() {
        AbstractClientPlayer entity = resolveEntity();
        if (entity == null) return null;
        // The brain runs on the owner's client, so the local player IS the owner.
        var localOwner = Minecraft.getInstance().player;
        String ownerName = localOwner != null ? localOwner.getName().getString() : "unknown";
        return "<env>\n"
                + "  entity_uuid: " + entityUuid + "\n"
                + "  owner_name: " + ownerName + "\n"
                + "  dimension: " + entity.level().dimension().location() + "\n"
                + "  today: " + LocalDate.now() + "\n"
                + "</env>";
    }

    private AbstractClientPlayer resolveEntity() {
        return ClientNumenLookup.resolve(entityUuid);
    }

    private void bounceBackToMain(int gen, NumenLlmClient.ChatResult res, Throwable err) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> handleResponse(gen, res, err));
    }

    private void handleResponse(int gen, NumenLlmClient.ChatResult res, Throwable err) {
        // Owner interrupted this turn while the call was in flight: abort()
        // already settled the conversation (and, if a newer turn has since
        // started, awaitingLlmResponse belongs to *that* call). Discard wholesale
        // — do NOT touch awaitingLlmResponse here, or we'd clear the newer turn's.
        if (gen != turnGeneration) {
            Constants.LOG.info("[numen-entity#{}] discarding interrupted LLM response (gen {} != {})",
                    entityUuid, gen, turnGeneration);
            return;
        }
        awaitingLlmResponse = false;

        // World is unloading (owner quit / disconnected): the client→server channel is gone, so a
        // dispatched ExecuteToolPayload would NPE in the platform sender. Drop this turn quietly.
        if (Minecraft.getInstance().getConnection() == null) {
            Constants.LOG.info("[numen-entity#{}] client disconnected — dropping LLM turn", entityUuid);
            aborted = true;
            return;
        }

        if (err != null) {
            Constants.LOG.warn("[numen-entity#{}] LLM call failed: {}",
                    entityUuid, unwrap(err));
            aborted = true;
            return;
        }
        if (res == null || res.turn() == null) {
            Constants.LOG.warn("[numen-entity#{}] LLM returned null turn", entityUuid);
            aborted = true;
            return;
        }
        AssistantTurn turn = res.turn();
        // True context size of the request we just made — the auto-compaction
        // signal. 0 when the backend sent no usage frame (then auto never fires).
        if (res.promptTokens() > 0) {
            lastPromptTokens = res.promptTokens();
        }

        convo.addAssistant(turn);

        if (!turn.hasToolCalls()) {
            // Final text reply — spoken to the owner. Chain settles; the next
            // prompt resumes the same conversation with a fresh turn count.
            if (!turn.content().isEmpty()) {
                Constants.LOG.info("[numen-entity#{}] assistant (final): {}",
                        entityUuid, turn.content());
            } else {
                Constants.LOG.info("[numen-entity#{}] assistant (final, empty content)", entityUuid);
            }
            convo.resetTurnCount();
            // A prompt that arrived during this final turn was buffered; now that
            // the chain has settled, start a fresh turn to answer it.
            if (!bufferedPrompts.isEmpty()) tryStartTurn();
            return;
        }

        // Queue every tool call for strictly serial dispatch — one in flight at
        // a time, the next starting only when the current one's result lands
        // (synchronous agent: one body, one brain, one thing at a time). The
        // loop stays blind to HOW each runs (NumenTool.invoke); results arrive
        // through complete(), synchronously for client-side tools or later via
        // onToolResult for ones shipped to the server body.
        turnQueue.addAll(turn.toolCalls());
        dispatchNext();
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
}
