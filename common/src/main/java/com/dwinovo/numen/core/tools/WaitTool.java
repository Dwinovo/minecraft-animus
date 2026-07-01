package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.WaitTaskRecord;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * SAMPLE (raw-NumenTool style, no @NumenAction). A world-action tool: parse args
 * with Gson, build a TaskRecord, hand it to core's per-companion queue; the
 * result returns later via the task lifecycle. Schema is written explicitly.
 */
public final class WaitTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_SECONDS = 60;
    private static final long DEADLINE_MARGIN_TICKS = 40L;

    /** The model-facing arguments — Gson fills this straight from the JSON. */
    private record Args(int seconds, String reason) {}

    @Override
    public String name() {
        return "wait";
    }

    @Override
    public String description() {
        return "Wait in place for the given number of seconds, doing nothing on "
                + "purpose. Use it when the next step depends on time passing (a furnace "
                + "batch, nightfall, an owner who said \"wait here\"). Max " + MAX_SECONDS
                + "s per call — call again after re-checking for longer waits. "
                + "Interruptible by the owner at any time.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("seconds", "How long to wait, in real seconds (1-" + MAX_SECONDS + ").", 1, MAX_SECONDS)
                .optionalString("reason", "Optional: why you're waiting (shown on the debug overlay).")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        int seconds = Math.max(1, Math.min(MAX_SECONDS, a.seconds()));
        String reason = a.reason() != null ? a.reason() : "";
        long deadline = companion.level().getGameTime() + seconds * 20L + DEADLINE_MARGIN_TICKS;
        CompanionTickDispatcher.queueFor(companion.getUUID())
                .enqueue(new WaitTaskRecord(toolCallId, deadline, seconds, reason));
        // No reply here: the task lifecycle ships the result when the wait completes.
    }
}
