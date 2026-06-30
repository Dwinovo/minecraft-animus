package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw NumenTool): read the current world state. */
public final class GetWorldInfoTool extends ServerNumenTool {

    private final PerceptionTools impl = new PerceptionTools();

    @Override
    public String name() {
        return "get_world_info";
    }

    @Override
    public String description() {
        return "Read the current world state: dimension, game-time tick counter, whether it's "
                + "bright or dark outside (combat / spawn planning), and weather (clear / rain / "
                + "thunder, affects sailing and combat). No arguments.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        reply.accept(impl.getWorldInfo(self));
    }
}
