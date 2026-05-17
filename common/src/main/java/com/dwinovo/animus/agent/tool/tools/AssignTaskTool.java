package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AgentRole;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ClientToolContext;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.agent.PlayerAgentLoop;
import com.dwinovo.animus.client.data.ClientPlayerAnimusState;
import com.dwinovo.animus.client.data.ClientUnitView;
import com.dwinovo.animus.network.payload.SummonUnitPayload;
import com.dwinovo.animus.platform.Services;
import com.google.gson.JsonObject;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code assign_task} tool — PlayerAgent's primary verb. Dispatches a
 * task to one of the player's six units. If the unit is currently idle,
 * the server summons it near the player; the client then routes the
 * stashed prompt to the freshly-spawned EntityAgent.
 *
 * <h2>Fire-and-forget</h2>
 * Returns {@code "dispatched"} immediately — does NOT wait for the
 * EntityAgent to finish. Completion flows back via the
 * {@code recent_reports} channel surfaced in the next PlayerAgent env block.
 * This is the deliberate divergence from opencode's sync {@code task} tool:
 * a game-tick loop can't block on a multi-minute task.
 *
 * <h2>Client-side busy check</h2>
 * Mirror state ({@link ClientPlayerAnimusState}) is consulted first; if
 * the unit is already active, fail immediately so the LLM doesn't pile
 * concurrent tasks on a busy unit. Server has an authoritative re-check
 * in {@code PlayerAnimusManager.summonUnit} as a safety net.
 */
public final class AssignTaskTool implements AnimusTool {

    @Override
    public String name() { return "assign_task"; }

    @Override
    public String description() {
        return "Dispatch a task prompt to one of your units (1-6). The unit "
                + "is summoned into the world near you, runs the task autonomously, "
                + "reports back via recent_reports, then despawns. Returns "
                + "'dispatched' immediately; do NOT wait for completion. Fails "
                + "if the target unit is busy with another task or dead.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("unit_id", Map.of("type", "integer",
                "description", "Target unit id, 1..6. See env block for which units are idle/busy."));
        properties.put("prompt", Map.of("type", "string",
                "description", "Natural-language task for the unit to perform."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("unit_id", "prompt"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() { return 1; }

    @Override
    public boolean isLocal() { return true; }

    @Override
    public Set<AgentRole> allowedRoles() { return EnumSet.of(AgentRole.PLAYER); }

    @Override
    public String executeLocal(JsonObject args, ClientToolContext ctx) {
        int unitId = readInt(args, "unit_id");
        String prompt = readString(args, "prompt");

        if (unitId < 1 || unitId > 6) {
            return "{\"success\":false,\"message\":\"unit_id must be 1..6\"}";
        }
        ClientUnitView view = ClientPlayerAnimusState.instance().unit(unitId);
        if (view.active()) {
            return "{\"success\":false,\"message\":\"unit " + unitId + " is busy with another task; call recall_unit first or wait for its report\"}";
        }
        if (!view.alive()) {
            return "{\"success\":false,\"message\":\"unit " + unitId + " is dead and respawning\"}";
        }

        // Stash the prompt locally; will be drained when UnitSpawnedPayload arrives.
        PlayerAgentLoop player = AgentLoopRegistry.playerAgent();
        player.stashPendingPrompt(unitId, prompt);
        Services.NETWORK.sendToServer(new SummonUnitPayload(unitId));

        return "{\"success\":true,\"message\":\"dispatched to unit " + unitId + "\",\"data\":{\"unit_id\":" + unitId + "}}";
    }

    private static int readInt(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        try { return args.get(key).getAsInt(); }
        catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be an integer");
        }
    }

    private static String readString(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        return args.get(key).getAsString();
    }
}
