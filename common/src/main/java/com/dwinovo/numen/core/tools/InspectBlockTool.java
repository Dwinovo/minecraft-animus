package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw NumenTool): inspect the single block at the given coordinates. */
public final class InspectBlockTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final PerceptionTools impl = new PerceptionTools();

    private record Args(int x, int y, int z) {}

    @Override
    public String name() {
        return "inspect_block";
    }

    @Override
    public String description() {
        return "Inspect a single block at the given integer coordinates. Returns block id, its "
                + "block-state properties when any (e.g. an end_portal_frame's has_eye/facing), "
                + "hardness, whether you have the correct tool in hand, an estimated dig-tick count, "
                + "and whether the block is in your 4.5-block mining reach. Call this before auto_mine "
                + "to confirm the operation will succeed, or to check which end_portal_frame cells "
                + "still need an ender_eye.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Block X.")
                .integer("y", "Block Y.")
                .integer("z", "Block Z.")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.inspectBlock(a.x(), a.y(), a.z(), self));
    }
}
