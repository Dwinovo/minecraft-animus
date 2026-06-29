package com.dwinovo.numen.agent.tool;

import java.util.Map;

/**
 * The whole contract of a tool: what the model sees, plus a single way to run
 * it. There is deliberately <b>nothing about Minecraft, the server, or how the
 * work happens</b> on this interface — no entity, no task queue, no
 * "run on server". The engine's only job is to present the tool to the LLM,
 * deliver the call, and route the result back into the conversation; what a
 * tool actually does (drive the companion body, hook an external service, call
 * a web API — anything) is entirely the tool's own business.
 *
 * <p>{@link #invoke(ToolCall)} is the one entry point. The tool gets the call
 * (its id, arguments, which companion it acts for) and a way to reply, and does
 * whatever it likes — completing immediately, going async, sending its own
 * packets — then reports the result through the {@link ToolCall}.
 *
 * <p>In practice tools are authored as {@code @NumenAction} methods and realised
 * by {@link NumenActionTool}, which is also where Numen's own Minecraft
 * execution (server reads, body tasks) lives — as an implementation detail of
 * that adapter, not part of this contract.
 */
public interface NumenTool {

    /** Tool name as the LLM sees it. {@code snake_case}. */
    String name();

    /**
     * Description shown to the LLM — the single biggest lever on whether the model
     * picks this tool correctly. Cover what it does, WHEN to use it (and when not),
     * what each non-obvious parameter means, and any caveat.
     */
    String description();

    /** JSON Schema (OpenAI tool-parameter dialect) for the tool's arguments. */
    Map<String, Object> parameterSchema();

    /**
     * Run this tool for one call. The tool does its work — now, later, on the
     * client, on the server, off in some external service — and reports the
     * result through the {@link ToolCall} (complete now, or hand off and complete
     * when ready). The agent loop calls only this and is otherwise blind to how
     * the tool runs.
     */
    void invoke(ToolCall call);
}
