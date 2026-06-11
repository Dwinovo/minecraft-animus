package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.WaitTaskRecord;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code wait} tool — deliberate idling. Smelting runs on real time,
 * night arrives on the clock, owners go AFK; without an explicit wait the
 * model burns tokens (and looks frantic) polling or inventing busywork to
 * fill those gaps.
 */
public final class WaitTool implements AnimusTool {

    /** Cap one wait at 5 minutes; longer vigils chain calls (each is a checkpoint). */
    private static final int MAX_SECONDS = 300;
    /** Headroom past the wait itself so the deadline never races the wake-up. */
    private static final long DEADLINE_MARGIN_TICKS = 100;

    @Override
    public String name() {
        return WaitTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Wait in place for the given number of seconds, doing nothing on "
                + "purpose. Use it when the next step depends on time passing: a "
                + "furnace batch (~10s per item), nightfall/daybreak, an owner who "
                + "said \"wait here\". Max " + MAX_SECONDS + "s per call — for "
                + "longer vigils, call it again after re-checking the situation "
                + "(each return is a natural checkpoint). The optional reason is "
                + "shown to the owner on the debug overlay. Interruptible by the "
                + "owner at any time.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("seconds", Map.of("type", "integer",
                "description", "How long to wait, in real seconds (1-" + MAX_SECONDS + ").",
                "minimum", 1, "maximum", MAX_SECONDS));
        properties.put("reason", Map.of("type", "string",
                "description", "Optional: why you're waiting (shown on the debug overlay)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("seconds"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return MAX_SECONDS * 20L + DEADLINE_MARGIN_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        if (!args.has("seconds") || args.get("seconds").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: seconds");
        }
        int seconds;
        try {
            seconds = args.get("seconds").getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("'seconds' must be an integer");
        }
        seconds = Math.clamp(seconds, 1, MAX_SECONDS);
        String reason = args.has("reason") && !args.get("reason").isJsonNull()
                ? args.get("reason").getAsString() : "";
        long deadline = currentGameTime + seconds * 20L + DEADLINE_MARGIN_TICKS;
        return new WaitTaskRecord(toolCallId, deadline, seconds, reason);
    }
}
