package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AgentRole;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ClientToolContext;
import com.dwinovo.animus.client.agent.AgentLoopRegistry;
import com.dwinovo.animus.client.agent.EntityAgentLoop;
import com.dwinovo.animus.client.data.ClientPlayerAnimusState;
import com.dwinovo.animus.client.data.ClientUnitView;
import com.dwinovo.animus.network.payload.RecallUnitPayload;
import com.dwinovo.animus.platform.Services;
import com.google.gson.JsonObject;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code recall_unit} tool — PlayerAgent's emergency abort for a unit
 * already mid-task. Local EntityAgentLoop is told to abort (pushes a
 * partial report) and a {@code RecallUnitPayload} ships to the server to
 * discard the in-world entity. Idempotent.
 */
public final class RecallUnitTool implements AnimusTool {

    @Override
    public String name() { return "recall_unit"; }

    @Override
    public String description() {
        return "Abort an in-flight task on a specific unit. The unit is "
                + "recalled from the world; a partial report is pushed back "
                + "noting the recall. Use when a unit is stuck or its task "
                + "is no longer needed.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("unit_id", Map.of("type", "integer",
                "description", "Unit id to recall (1..6). Must be currently active."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("unit_id"));
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
        if (unitId < 1 || unitId > 6) {
            return "{\"success\":false,\"message\":\"unit_id must be 1..6\"}";
        }
        ClientUnitView view = ClientPlayerAnimusState.instance().unit(unitId);
        if (!view.active()) {
            return "{\"success\":false,\"message\":\"unit " + unitId + " is not active; nothing to recall\"}";
        }

        Optional<EntityAgentLoop> loop = AgentLoopRegistry.findByUnitId(unitId);
        if (loop.isPresent()) {
            // Full cleanup path — pushes report, sends recall packet, disposes.
            loop.get().externalAbort("recalled by PlayerAgent", true);
        } else {
            // No local loop (lifecycle race) — at least tell the server.
            Services.NETWORK.sendToServer(new RecallUnitPayload(unitId));
            // Optimistically update local mirror; server snapshot will re-confirm.
            ClientPlayerAnimusState.instance().setUnit(unitId, view.withActive(false));
        }
        return "{\"success\":true,\"message\":\"recalled unit " + unitId + "\"}";
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
}
