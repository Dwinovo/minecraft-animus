package com.dwinovo.numen.core.tool;

import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.core.net.CancelTasksPayload;
import com.dwinovo.numen.core.net.ExecuteToolPayload;
import com.dwinovo.numen.platform.Services;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * numen-core's client-side tool transport — how a body-bound tool actually reaches
 * the server and comes back, entirely core's own packets. The engine scheduler
 * hands a {@link ToolCall} to a tool's {@code invoke}; a body-bound tool calls
 * {@link #ship} (sends core's {@link ExecuteToolPayload} and parks the call by
 * id); when core's {@code TaskResultPayload} returns, {@link #deliver} completes
 * that call. The engine knows none of this — to it the tool simply completes
 * later.
 */
public final class CoreServerTools {

    private static final Map<String, ToolCall> IN_FLIGHT = new ConcurrentHashMap<>();

    private CoreServerTools() {}

    /** Ship a body-bound tool to the server and park its call until the result returns. */
    public static void ship(ToolCall call) {
        UUID entity = call.ctx().entityUuid();
        IN_FLIGHT.put(call.id(), call);
        Services.NETWORK.sendToServer(
                new ExecuteToolPayload(entity, call.id(), call.toolName(), call.rawArgs()));
    }

    /** A server result came back (core's TaskResultPayload) — complete the parked call. */
    public static void deliver(String toolCallId, String resultJson) {
        ToolCall call = IN_FLIGHT.remove(toolCallId);
        if (call != null) call.complete(resultJson);
    }

    /** Owner interrupted: forget this companion's parked calls and tell the body to stop. */
    public static void abort(UUID companionUuid) {
        IN_FLIGHT.values().removeIf(c -> companionUuid.equals(c.ctx().entityUuid()));
        Services.NETWORK.sendToServer(new CancelTasksPayload(companionUuid));
    }
}
