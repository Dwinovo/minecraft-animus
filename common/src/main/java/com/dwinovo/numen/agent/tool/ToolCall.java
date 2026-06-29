package com.dwinovo.numen.agent.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.function.Consumer;

/**
 * One in-flight tool call handed to {@link NumenTool#invoke}. It is the entire
 * surface a tool needs to run and report back — the agent loop hands the tool
 * this object and then forgets about <em>how</em> the work happens. A tool
 * reports its result through exactly one of two verbs:
 *
 * <ul>
 *   <li>{@link #complete(String)} — "I finished, here is the result." For a
 *       tool that did its work synchronously on the agent (client) thread.</li>
 *   <li>{@link #shipToServer()} — "hand this to the body." The call returns
 *       immediately; the result arrives later (via {@code TaskResultPayload})
 *       and the loop completes it then.</li>
 * </ul>
 *
 * The agent loop neither knows nor cares which verb a tool uses — that choice
 * belongs entirely to the tool. This is the seam that makes tool execution
 * transparent to {@code EntityAgentLoop}: it dispatches a call and is notified
 * of the result, with no branch on tool category.
 *
 * <p>A future MCP-bridge tool can ignore both built-in verbs' transports and,
 * inside {@link NumenTool#invoke}, do whatever it likes (proxy to an external
 * server, hook a chat app, read a file) on any thread, then call
 * {@link #complete} when done.
 */
public final class ToolCall {

    private final String id;
    private final String toolName;
    private final String rawArgs;
    private final ClientToolContext ctx;
    private final Consumer<String> completion;   // result sink for synchronous (client-side) tools
    private final Runnable serverDispatch;        // ship-to-body behaviour, supplied by the loop

    public ToolCall(String id, String toolName, String rawArgs, ClientToolContext ctx,
                    Consumer<String> completion, Runnable serverDispatch) {
        this.id = id;
        this.toolName = toolName;
        this.rawArgs = rawArgs;
        this.ctx = ctx;
        this.completion = completion;
        this.serverDispatch = serverDispatch;
    }

    /** The LLM's {@code tool_call} id — carried through to the matching result message. */
    public String id() { return id; }

    public String toolName() { return toolName; }

    /** Live entity / world handle for client-side tools. Entity may be {@code null} (out of view). */
    public ClientToolContext ctx() { return ctx; }

    /** Raw argument JSON string exactly as the model emitted it. */
    public String rawArgs() { return rawArgs; }

    /**
     * Parsed arguments. Throws {@link IllegalArgumentException} on malformed
     * JSON; {@link NumenTool#invoke} catches it and reports a failed result so
     * the conversation continues.
     */
    public JsonObject args() {
        if (rawArgs == null || rawArgs.isBlank()) return new JsonObject();
        try {
            return JsonParser.parseString(rawArgs).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid arguments JSON: " + ex.getMessage());
        }
    }

    /** Deliver the result for a tool that finished synchronously on the client. */
    public void complete(String resultJson) {
        completion.accept(resultJson);
    }

    /** Hand the call to the server body; the loop completes it when the result returns. */
    public void shipToServer() {
        serverDispatch.run();
    }
}
