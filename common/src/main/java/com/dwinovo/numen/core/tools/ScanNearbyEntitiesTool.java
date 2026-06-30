package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw NumenTool): list nearby entities, sorted by distance. */
public final class ScanNearbyEntitiesTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final QueryExtraTools impl = new QueryExtraTools();

    private record Args(double radius, String type_filter) {}

    @Override
    public String name() {
        return "scan_nearby_entities";
    }

    @Override
    public String description() {
        return "List entities within a radius around you, sorted by distance. Use type_filter to "
                + "narrow: 'hostile' for monsters, 'passive' for animals/items, 'player' for players, "
                + "'all' for everything. Returns at most 20 entities; truncated:true means more exist. "
                + "Each entry has id, type, position, distance, hp, and category. To fight mobs, use "
                + "hunt (it scans by type itself).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .number("radius", "Search radius in blocks. Range [1, 64].", 1, 64)
                .enumStr("type_filter", "One of: hostile, passive, player, all.",
                        "hostile", "passive", "player", "all")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.scanNearbyEntities(a.radius(), a.type_filter(), self));
    }
}
