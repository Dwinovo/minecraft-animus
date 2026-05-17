package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.AttackTargetTaskRecord;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code attack_target} tool — sets the entity's combat target and
 * leaves the kill to the entity's permanently-registered vanilla
 * {@code MeleeAttackGoal}. Resolves only when the target dies, the entity
 * dies, or the 5-minute deadline hits.
 *
 * <h2>Schema</h2>
 * <pre>
 * {
 *   "type": "object",
 *   "properties": {
 *     "target_entity_id": { "type": "integer" }
 *   },
 *   "required": ["target_entity_id"],
 *   "additionalProperties": false
 * }
 * </pre>
 *
 * <h2>Where the LLM gets {@code target_entity_id}</h2>
 * Today: from chat with the owner ("attack zombie id 42") or from future
 * perception tools (e.g. {@code scan_nearby_entities}) once those land. The
 * server-side goal will return {@code FAILED / "target lost"} if the id
 * doesn't resolve to a living entity, so a bad id is recoverable for the
 * LLM rather than a silent stall.
 *
 * <h2>Why no {@code timeout_seconds} parameter</h2>
 * The user-facing semantic is "set and forget until one of three terminal
 * conditions". A hard-coded 5-minute upper bound covers every realistic
 * combat horizon (Ender Dragon ≈ 8 min only if you under-DPS; that's the
 * one outlier and the LLM can re-issue). Keeping the schema to a single
 * required field also dodges the "every property must be in required"
 * constraint that strict-mode JSON-schema upholds.
 */
public final class AttackTargetTool implements AnimusTool {

    /** 5 minutes at vanilla 20 tps. */
    private static final long DEFAULT_TIMEOUT_TICKS = 5 * 60 * 20;

    @Override
    public String name() {
        return AttackTargetTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Engage a hostile target in combat. The entity will chase and "
                + "melee-attack the given target until one of: the target dies "
                + "(success), the entity dies (cancelled), or 5 minutes elapse "
                + "(timeout). Use a target_entity_id obtained from the owner or "
                + "from a perception tool — guessing IDs will fail fast with "
                + "'target lost'.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("target_entity_id", Map.of("type", "integer",
                "description", "Vanilla entity id of the target to attack."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("target_entity_id"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return DEFAULT_TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        int targetEntityId = requireInt(args, "target_entity_id");
        long deadline = currentGameTime + DEFAULT_TIMEOUT_TICKS;
        return new AttackTargetTaskRecord(toolCallId, deadline, targetEntityId);
    }

    private static int requireInt(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "argument '" + key + "' must be an integer: " + ex.getMessage());
        }
    }
}
