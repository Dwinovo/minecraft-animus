package com.dwinovo.animus.client.agent;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.agent.llm.AnimusLlmClient;
import com.dwinovo.animus.agent.llm.ConvoLog;
import com.dwinovo.animus.agent.llm.ConvoState;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.LlmToolCall;
import com.dwinovo.animus.agent.skill.SkillRegistry;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ClientToolContext;
import com.dwinovo.animus.agent.tool.ToolRegistry;
import com.dwinovo.animus.network.payload.CancelTasksPayload;
import com.dwinovo.animus.network.payload.ExecuteToolPayload;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.platform.services.IAnimusConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-entity agent loop running on the <strong>client</strong>. One instance
 * per Animus the player talks to, keyed by the stable {@code entity.getUUID()}
 * in {@link AgentLoopRegistry} and resolved to the current body via
 * {@link ClientAnimusLookup} (so it survives the int-id churn of dimension
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
 *   <li>LLM response — {@link AnimusLlmClient#chatStreaming} resolves on the
 *       HTTP executor thread; {@link #bounceBackToMain} hops via
 *       {@code Minecraft.getInstance().execute} before any mutation</li>
 * </ul>
 */
public final class EntityAgentLoop {

    /**
     * Persona prompt for a single Animus body. Deliberately does NOT enumerate
     * tools — the live tool list (with full descriptions) rides along on every
     * request, and a prose copy here rotted badly once already. This prompt
     * carries only what the tool schemas can't: identity, working discipline,
     * and the voice toward the owner.
     */
    private static final String ENTITY_PROMPT = """

            You are an Animus — a loyal companion unit in Minecraft, bound to one
            owner. You have a real body in the world and act through it with the
            tools provided on each request. Be capable and concise: get the
            owner's intent done, then say what happened in a few words.

            <operating_principles>
            - Act, don't narrate. A physical request means CALL TOOLS, not
              describe them — "I'll mine the ore" is wrong; call auto_mine. Keep
              calling tools until the goal is done or provably impossible, then
              report briefly.
            - Verify, don't assume. get_self_status is your whole self in one
              call — HP, position, equipment AND full inventory; the world comes
              from the scan/inspect tools. NEVER claim an item, or a finished
              job, that a tool result hasn't confirmed.
            - Failed results teach. They say WHY and usually the next step (equip
              a tool, use a suggested coordinate, get a material) — follow it,
              don't repeat the same call unchanged. Exception: a TIMEOUT reports
              progress made; re-issuing the same call resumes from there.
            - Reuse the world. <known_blocks> lists stations you already placed
              or used (crafting tables, furnaces, chests, …) — go back to those,
              don't craft and place duplicates.
            - Plan only what's big. Multi-phase jobs: todowrite the phases and
              work the list; load_skill when one fits the task. One-step
              requests: just do them.
            </operating_principles>

            <choosing_actions>
            Heuristics for the common confusions — pick the tool that matches the
            intent, the live schemas carry the details:
            - To USE a station (crafting table, furnace, chest, any block with a
              GUI), interact_at its coordinate — that paths you there safely and
              opens it. Do NOT move_to onto a station; you'd just path into the
              block.
            - To craft, lookup_recipe for the grid layout, then transfer each
              ingredient into its cell. A 2x2 recipe uses your own grid (inspect_gui
              with nothing open); a 3x3 needs a crafting table (interact_at it,
              then inspect_gui shows the grid as a slot-number map). Place ONE item
              per cell (count:1), match the layout top-left, then transfer the
              result slot out (no `to`). To smelt, interact_at a furnace and transfer
              the input + fuel. To move items in/out of any container, use transfer.
              lookup_recipe if unsure.
            - move_to is for getting somewhere to STAND. If it reports no path or
              stops far short, that spot is unreachable or too far — pick a
              NEARER waypoint, or scan first; don't repeat the same target.
            - Don't place a block where one already is — the result tells you
              what's there; build somewhere clear instead.
            </choosing_actions>

            <communication>
            - Your text is spoken aloud to the owner — reply in the owner's
              language, one short natural paragraph. Tool calls are silent; only
              your text is shown.
            - Narrate by acting, not by posting each step. Speak when you have a
              result or a real question.
            </communication>

            <examples>
            owner: 去挖10块铁
            → equip_item(stone_pickaxe), auto_mine(iron_ore + deepslate_iron_ore, 10) … (act)
            → "挖到了 10 块铁,已经带回来了。"

            owner: 用之前那个熔炉烧点铁
            → interact_at(<furnace coordinate from known_blocks>), load the iron + fuel … (act)
            → "在烧了,熟铁马上好。"

            owner: 那边那个僵尸危险吗
            → scan_nearby_entities(radius=24)
            → "西边 12 格有一只僵尸,要我去清掉吗?"
            </examples>
            """;

    // ---- context compaction (mirrors Claude Code's /compact machinery) ----

    /**
     * Assumed model context window. TODO: surface as a config field once the
     * settings GUI grows a slot for it; 64k matches the smallest window among
     * the supported providers' current flagship chat models.
     */
    private static final int CONTEXT_WINDOW_TOKENS = 64_000;
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
            + "between a Minecraft companion entity (the Animus) and its owner.";

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
    /** JSONL persistence under {@code config/animus/conversations/<uuid>.jsonl}. */
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
        Path animusRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("animus");
        this.log = ConvoLog.forEntity(animusRoot.resolve("conversations"), entityUuid);
        this.convo = new ConvoState(log::append);
        this.workBlocks = WorkBlockMemory.forEntity(animusRoot.resolve("memory"), entityUuid);
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
        Constants.LOG.info("[animus-entity#{}] restored {} msg(s) from disk{}",
                entityUuid, history.size(),
                dangling.isEmpty() ? "" : " (healed " + dangling.size() + " dangling tool call(s))");
    }

    public UUID entityUuid() { return entityUuid; }
    public ConvoState convo() { return convo; }

    /** Owner typed a prompt in the chat GUI. */
    public void submitPrompt(String text) {
        if (dead) {
            Constants.LOG.info("[animus-entity#{}] prompt ignored — body is dead", entityUuid);
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
        Constants.LOG.info("[animus-entity#{}] user prompt ({} chars){}{}: {}",
                entityUuid, text.length(),
                wasAborted ? " — reset previous abort" : "",
                deferred ? " — buffered (mid-turn)" : "",
                truncate(text, 200));
        tryStartTurn();
    }

    /** Server reported a world-action tool result for one of our outstanding calls. */
    public void onToolResult(String toolCallId, String resultJson) {
        String toolName = pendingToolCalls.remove(toolCallId);
        if (toolName == null) {
            Constants.LOG.debug("[animus-entity#{}] late tool_result id={} ignored",
                    entityUuid, toolCallId);
            return;
        }
        harvestWorkBlocks(toolName, resultJson);
        convo.addToolResult(toolCallId, resultJson);
        Constants.LOG.info("[animus-entity#{}] tool_result id={} (pending={}) → {}",
                entityUuid, toolCallId, pendingToolCalls.size(),
                truncate(resultJson, 200));
        if (pendingToolCalls.isEmpty()) tryStartTurn();
    }

    /**
     * Pull functional-block coordinates out of successful tool results into
     * {@link WorkBlockMemory}. The results already carry them (craft reports
     * the table it used, the furnace tools report the furnace, place_block
     * reports what it placed) — this just stops the loop from forgetting them
     * once the result scrolls out of context.
     */
    private void harvestWorkBlocks(String toolName, String resultJson) {
        try {
            JsonObject root = JsonParser.parseString(resultJson).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) return;
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : null;
            if (data == null) return;

            switch (toolName) {
                case "craft" -> {
                    if (data.has("crafting_table_x")) {
                        workBlocks.record("crafting_table", new net.minecraft.core.BlockPos(
                                data.get("crafting_table_x").getAsInt(),
                                data.get("crafting_table_y").getAsInt(),
                                data.get("crafting_table_z").getAsInt()));
                    }
                }
                case "load_furnace", "check_furnace", "collect_furnace" -> {
                    if (data.has("x")) {
                        workBlocks.record("furnace", new net.minecraft.core.BlockPos(
                                data.get("x").getAsInt(),
                                data.get("y").getAsInt(),
                                data.get("z").getAsInt()));
                    }
                }
                // The chest-storage tools also report which container block they
                // used (data.block + x/y/z) — same harvest as place_block.
                case "place_block", "deposit_items", "take_items" -> {
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
            Constants.LOG.debug("[animus-entity#{}] work-block harvest skipped: {}",
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
            Constants.LOG.info("[animus-entity#{}] interrupted by owner (awaitingLlm={}, cancelledTools={}, queued={})",
                    entityUuid, wasAwaitingLlm, cancelledTools, bufferedPrompts.size());
        } else if (!bufferedPrompts.isEmpty()) {
            // Priority 2: idle — drop the held queue.
            int dropped = bufferedPrompts.size();
            bufferedPrompts.clear();
            Constants.LOG.info("[animus-entity#{}] interrupt cleared {} queued prompt(s)",
                    entityUuid, dropped);
        }
    }

    /**
     * The body died — the server tells us via {@code AnimusDeathPayload} with the death cause. SUSPEND
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
        deathInterruptedCalls = new java.util.ArrayList<>(pendingToolCalls.keySet());
        turnGeneration++;          // discard any in-flight LLM response (halt output)
        awaitingLlmResponse = false;
        compacting = false;
        pendingToolCalls.clear();
        bufferedPrompts.clear();
        dead = true;
        Constants.LOG.info("[animus-entity#{}] body died ({}) — loop frozen ({} call(s) in flight)",
                entityUuid, cause, deathInterruptedCalls.size());
    }

    /**
     * The body respawned at its owner after dying — thaw the frozen loop and ONLY NOW restore context:
     * resolve any tool call that was interrupted by the death (so the conversation is valid and the
     * brain learns its task was cut short), then inject a {@code <event>} detailing the death cause.
     * Nothing was fed to the model while dead, so it stayed fully stopped for the whole timer.
     */
    public void onRespawned() {
        if (!dead) return;
        dead = false;
        for (String id : deathInterruptedCalls) {
            convo.addToolResult(id, "{\"success\":false,\"message\":\""
                    + escape("任务因你死亡而中断") + "\"}");
        }
        deathInterruptedCalls = List.of();
        if (convo.lastMessage() instanceof ConvoState.Msg.User) {
            convo.addAssistant(new AssistantTurn("(已中断)", List.of(), null));
        }
        String cause = deathCause == null ? "未知原因" : deathCause.replace('<', '(').replace('>', ')');
        deathCause = null;
        Constants.LOG.info("[animus-entity#{}] respawned — loop thawed", entityUuid);
        injectEvent("<event kind=\"death\">你刚才死了(" + cause
                + "),手头的任务被中断;现已在主人身边复活。先看看状况,继续或重新规划。</event>", true);
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
        Constants.LOG.info("[animus-entity#{}] event queued{}: {}",
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
            Constants.LOG.debug("[animus-entity#{}] tryStartTurn skipped: body dead", entityUuid);
            return;
        }
        if (aborted) {
            Constants.LOG.debug("[animus-entity#{}] tryStartTurn skipped: aborted", entityUuid);
            return;
        }
        if (awaitingLlmResponse) {
            Constants.LOG.debug("[animus-entity#{}] tryStartTurn skipped: awaitingLlmResponse", entityUuid);
            return;
        }
        if (compacting) {
            Constants.LOG.debug("[animus-entity#{}] tryStartTurn skipped: compacting", entityUuid);
            return;
        }
        if (!pendingToolCalls.isEmpty()) {
            Constants.LOG.debug("[animus-entity#{}] tryStartTurn skipped: {} tool result(s) pending",
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
        if (!AnimusLlmClient.isConfigured()) {
            Constants.LOG.warn("[animus-entity#{}] API key not set; open the Animus GUI (X) → Settings",
                    entityUuid);
            aborted = true;
            return;
        }

        // Auto-compaction gate: the last request's true context size (as the
        // API counted it) is within the buffer of the window — summarize FIRST,
        // then this method re-runs and dispatches the turn on the compacted
        // history. Mirrors Claude Code's autoCompactIfNeeded.
        if (lastPromptTokens >= CONTEXT_WINDOW_TOKENS - AUTO_COMPACT_BUFFER_TOKENS
                && convo.snapshot().size() >= MIN_COMPACT_MESSAGES
                && compactFailures < MAX_COMPACT_FAILURES) {
            Constants.LOG.info("[animus-entity#{}] auto-compacting: last prompt {} tokens >= {} - {}",
                    entityUuid, lastPromptTokens, CONTEXT_WINDOW_TOKENS, AUTO_COMPACT_BUFFER_TOKENS);
            startCompaction(true);
            return;
        }

        convo.incrementTurn();
        awaitingLlmResponse = true;

        var tools = ToolRegistry.all();
        var snapshot = convo.snapshot();
        IAnimusConfig config = Services.CONFIG;
        String systemPrompt = composeSystemPrompt(config.getSystemPrompt());

        Constants.LOG.info("[animus-entity#{}] turn {}: convo={} msgs, tools={}",
                entityUuid, convo.turnCount(), snapshot.size(), tools.size());

        // Capture the current generation; if the owner interrupts before this
        // call resolves, handleResponse sees the mismatch and discards it.
        final int gen = turnGeneration;
        AnimusLlmClient.instance().chatStreaming(snapshot, tools, systemPrompt, null)
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
            Constants.LOG.info("[animus-entity#{}] manual compact ignored (busy={}, msgs={})",
                    entityUuid, isBusy(), convo.snapshot().size());
            return;
        }
        if (!AnimusLlmClient.isConfigured()) return;
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
        Constants.LOG.info("[animus-entity#{}] compaction started ({}, {} msgs)",
                entityUuid, auto ? "auto" : "manual", request.size() - 1);
        final int gen = turnGeneration;
        AnimusLlmClient.instance().chatStreaming(request, List.of(), COMPACT_SYSTEM_PROMPT, null)
                .whenComplete((res, err) -> Minecraft.getInstance().execute(
                        () -> finishCompaction(gen, auto, res, err)));
    }

    private void finishCompaction(int gen, boolean auto,
                                  AnimusLlmClient.ChatResult res, Throwable err) {
        if (gen != turnGeneration) {
            Constants.LOG.info("[animus-entity#{}] discarding interrupted compaction (gen {} != {})",
                    entityUuid, gen, turnGeneration);
            return;   // abort() already reset the compacting flag
        }
        compacting = false;

        String summary = (err == null && res != null) ? res.turn().content() : null;
        if (summary == null || summary.isBlank()) {
            compactFailures++;
            Constants.LOG.warn("[animus-entity#{}] compaction failed ({}/{}): {}",
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
        Constants.LOG.info("[animus-entity#{}] compaction done ({}): history → 1 summary msg ({} chars)",
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
                + "  dimension: " + entity.level().dimension().identifier() + "\n"
                + "  today: " + LocalDate.now() + "\n"
                + "</env>";
    }

    private AbstractClientPlayer resolveEntity() {
        return ClientAnimusLookup.resolve(entityUuid);
    }

    private void bounceBackToMain(int gen, AnimusLlmClient.ChatResult res, Throwable err) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> handleResponse(gen, res, err));
    }

    private void handleResponse(int gen, AnimusLlmClient.ChatResult res, Throwable err) {
        // Owner interrupted this turn while the call was in flight: abort()
        // already settled the conversation (and, if a newer turn has since
        // started, awaitingLlmResponse belongs to *that* call). Discard wholesale
        // — do NOT touch awaitingLlmResponse here, or we'd clear the newer turn's.
        if (gen != turnGeneration) {
            Constants.LOG.info("[animus-entity#{}] discarding interrupted LLM response (gen {} != {})",
                    entityUuid, gen, turnGeneration);
            return;
        }
        awaitingLlmResponse = false;

        // World is unloading (owner quit / disconnected): the client→server channel is gone, so a
        // dispatched ExecuteToolPayload would NPE in the platform sender. Drop this turn quietly.
        if (Minecraft.getInstance().getConnection() == null) {
            Constants.LOG.info("[animus-entity#{}] client disconnected — dropping LLM turn", entityUuid);
            aborted = true;
            return;
        }

        if (err != null) {
            Constants.LOG.warn("[animus-entity#{}] LLM call failed: {}",
                    entityUuid, unwrap(err));
            aborted = true;
            return;
        }
        if (res == null || res.turn() == null) {
            Constants.LOG.warn("[animus-entity#{}] LLM returned null turn", entityUuid);
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
                Constants.LOG.info("[animus-entity#{}] assistant (final): {}",
                        entityUuid, turn.content());
            } else {
                Constants.LOG.info("[animus-entity#{}] assistant (final, empty content)", entityUuid);
            }
            convo.resetTurnCount();
            // A prompt that arrived during this final turn was buffered; now that
            // the chain has settled, start a fresh turn to answer it.
            if (!bufferedPrompts.isEmpty()) tryStartTurn();
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
                        entityUuid, tc.name(), tc.id());
                convo.addToolResult(tc.id(),
                        "{\"success\":false,\"message\":\"unknown tool: " + escape(tc.name()) + "\"}");
                continue;
            }
            if (tool.isLocal()) {
                String resultJson;
                try {
                    JsonObject args = parseArgs(tc.arguments());
                    ClientToolContext ctx = new ClientToolContext(resolveEntity(), entityUuid);
                    resultJson = tool.executeLocal(args, ctx);
                } catch (RuntimeException ex) {
                    resultJson = "{\"success\":false,\"message\":\"" + escape(ex.getMessage()) + "\"}";
                    Constants.LOG.warn("[animus-entity#{}] local tool {} failed (id={}): {}",
                            entityUuid, tc.name(), tc.id(), ex.getMessage());
                }
                convo.addToolResult(tc.id(), resultJson);
                Constants.LOG.info("[animus-entity#{}] local-exec tool={} id={} → {}",
                        entityUuid, tc.name(), tc.id(), truncate(resultJson, 200));
                continue;
            }
            // World-action tool: ship to server with our vanilla entity id.
            // No client-side timeout — the server always returns a result, even
            // on entity death/removal (AnimusEntity.remove flushes CANCELLED
            // results synchronously), so the loop never waits forever.
            pendingToolCalls.put(tc.id(), tc.name());
            Services.NETWORK.sendToServer(new ExecuteToolPayload(
                    entityUuid, tc.id(), tc.name(), tc.arguments()));
            Constants.LOG.info("[animus-entity#{}] dispatch tool={} id={} args={}",
                    entityUuid, tc.name(), tc.id(), truncate(tc.arguments(), 200));
        }

        // If nothing is awaiting a server round-trip (all local-exec / rejected),
        // kick the next turn immediately so the LLM sees the new tool_results.
        if (pendingToolCalls.isEmpty()) tryStartTurn();
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
