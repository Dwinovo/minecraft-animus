package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.ClientToolContext;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolInvocation;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.network.payload.ExecuteToolPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes one agent turn's tool calls and hands the results back — the entire
 * "run a tool call, get a result" concern, lifted out of {@link EntityAgentLoop}
 * so the loop stays pure conversation/turn management.
 *
 * <h2>One synchronous serial queue</h2>
 * Calls run strictly one at a time: the next is dispatched only when the current
 * one's result lands (one body, one brain, one thing at a time — no async, no
 * concurrency). A tool either completes immediately on the client
 * ({@link ToolCall#complete}) or ships itself to the server body and completes
 * later when {@link #deliver} is called; the dispatcher is blind to which.
 *
 * <p>It reports back to its owner through the {@link Sink}: each landed result
 * via {@link Sink#onResult}, and {@link Sink#onAllSettled} once the turn's calls
 * are all done.
 */
public final class ToolDispatcher {

    /** The dispatcher's only line back to the agent loop. */
    public interface Sink {
        /** A result landed for {@code inv} — record it into the conversation. */
        void onResult(ToolInvocation inv, String resultJson);
        /** Every call this turn has settled — the loop may start the next LLM turn. */
        void onAllSettled();
        /** The live client-side body (for client-run tools); may be null when out of view. */
        AbstractClientPlayer entity();
    }

    /**
     * Wall-clock backstop (epoch millis) for the single in-flight call, 0 when idle.
     * Only rescues a dead-server / never-replying tool — deliberately generous, so a
     * core tool (always answered by the server) never trips it.
     */
    private static final long TOOL_BACKSTOP_MILLIS = 15 * 60 * 1000L;

    private final UUID entityUuid;
    private final Sink sink;

    /** This turn's remaining calls, drained one at a time. */
    private final Deque<ToolInvocation> queue = new ArrayDeque<>();
    /** The single in-flight call (id → invocation); ≤1 under the serial model. */
    private final Map<String, ToolInvocation> inFlight = new HashMap<>();
    /** Reentrancy guard so a synchronously-completing tool keeps the drain iterative. */
    private boolean advancing = false;
    private long deadlineMillis = 0;

    public ToolDispatcher(UUID entityUuid, Sink sink) {
        this.entityUuid = entityUuid;
        this.sink = sink;
    }

    /** Anything outstanding (in flight or still queued)? */
    public boolean busy() {
        return !inFlight.isEmpty() || !queue.isEmpty();
    }

    /** Run this turn's tool calls, serially. */
    public void dispatch(List<ToolInvocation> calls) {
        queue.addAll(calls);
        drainNext();
    }

    /** A server-body result came back (from {@code TaskResultPayload}). */
    public void deliver(String invocationId, String resultJson) {
        ToolInvocation inv = inFlight.get(invocationId);
        if (inv == null) {
            Constants.LOG.debug("[numen-dispatch#{}] late tool_result id={} ignored", entityUuid, invocationId);
            return;
        }
        complete(inv, resultJson);
    }

    /** Per-tick backstop: fail a never-replying in-flight call so the loop can't wedge. */
    public void tick() {
        if (deadlineMillis == 0 || inFlight.isEmpty()) return;
        if (System.currentTimeMillis() < deadlineMillis) return;
        ToolInvocation inv = inFlight.values().iterator().next();
        Constants.LOG.warn("[numen-dispatch#{}] tool {} id={} hit backstop timeout — failing it",
                entityUuid, inv.name(), inv.id());
        complete(inv, TaskResult.fail("tool timed out (no result returned)").toJson());
    }

    /**
     * Abandon everything outstanding (in flight + queued) and return their ids so the
     * caller can heal the conversation. Used on owner-interrupt and on death.
     */
    public List<String> cancelAndDrain() {
        List<String> ids = new ArrayList<>(inFlight.keySet());
        for (ToolInvocation inv : queue) ids.add(inv.id());
        inFlight.clear();
        queue.clear();
        deadlineMillis = 0;
        advancing = false;
        return ids;
    }

    /**
     * Drain the serial queue: dispatch the next call, or — when the queue is empty
     * and nothing is in flight — signal the turn is settled. Exactly one call
     * occupies the in-flight slot at a time; {@link #complete} re-enters here to
     * advance. The {@link #advancing} guard keeps a synchronously-completing tool
     * draining iteratively instead of recursing.
     */
    private void drainNext() {
        if (advancing) return;
        advancing = true;
        try {
            while (inFlight.isEmpty()) {
                ToolInvocation inv = queue.poll();
                if (inv == null) {
                    sink.onAllSettled();
                    return;
                }
                NumenTool tool = ToolRegistry.resolve(inv.name());
                if (tool == null) {
                    Constants.LOG.warn("[numen-dispatch#{}] LLM called unknown tool '{}' (id={})",
                            entityUuid, inv.name(), inv.id());
                    sink.onResult(inv, TaskResult.fail("unknown tool: " + inv.name()).toJson());
                    continue;   // nothing in flight — drain the next queued call
                }
                inFlight.put(inv.id(), inv);
                deadlineMillis = System.currentTimeMillis() + TOOL_BACKSTOP_MILLIS;
                ToolCall call = new ToolCall(inv.id(), inv.name(), inv.argsJson(),
                        new ClientToolContext(sink.entity(), entityUuid),
                        json -> complete(inv, json),
                        () -> Services.NETWORK.sendToServer(
                                new ExecuteToolPayload(entityUuid, inv.id(), inv.name(), inv.argsJson())));
                Constants.LOG.info("[numen-dispatch#{}] dispatch tool={} id={} args={}",
                        entityUuid, inv.name(), inv.id(), truncate(inv.argsJson()));
                try {
                    tool.invoke(call);
                } catch (RuntimeException ex) {
                    Constants.LOG.warn("[numen-dispatch#{}] tool {} threw (id={}): {}",
                            entityUuid, inv.name(), inv.id(), ex.getMessage());
                    complete(inv, TaskResult.fail(ex.getMessage()).toJson());
                }
                // Client tool: complete() cleared the slot → loop drains the next.
                // Server tool: slot occupied → exit and wait for deliver().
            }
        } finally {
            advancing = false;
        }
    }

    private void complete(ToolInvocation inv, String resultJson) {
        if (inFlight.remove(inv.id()) == null) {
            return;   // already settled by cancel/timeout, or a duplicate/late reply
        }
        deadlineMillis = 0;
        Constants.LOG.info("[numen-dispatch#{}] tool_result id={} tool={} (queued={}) → {}",
                entityUuid, inv.id(), inv.name(), queue.size(), truncate(resultJson));
        sink.onResult(inv, resultJson);
        // Advance unless drainNext is already looping (it picks up the next itself).
        if (!advancing) drainNext();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
