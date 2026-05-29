package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ClientToolContext;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code get_world_info} tool — read dimension / brightness outside /
 * weather / game-time. Used for combat planning (hostiles spawn in the
 * dark), navigation (don't sail in thunderstorms), and bed mechanics.
 *
 * <h2>Time fields</h2>
 * MC 26.1.2 refactored time-of-day onto a new {@code WorldClock} subsystem
 * that's not trivially queryable from a {@code Level}; we expose the raw
 * {@code game_time} tick counter (always available via {@code LevelData})
 * plus the {@code is_bright_outside} / {@code is_dark_outside} booleans
 * that vanilla now uses for "is it day" semantics. Moon phase is dropped
 * for now — not on {@code Level} anymore in 26.1.2.
 */
public final class GetWorldInfoTool implements AnimusTool {

    @Override
    public String name() {
        return "get_world_info";
    }

    @Override
    public String description() {
        return "Read the current world state: dimension, game-time tick counter, "
                + "whether it's bright or dark outside (combat / spawn planning), "
                + "and weather (clear / rain / thunder, affects sailing and combat). "
                + "No arguments.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        schema.put("required", List.of());
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return 1;
    }

    @Override
    public boolean isLocal() {
        return true;
    }


    @Override
    public String executeLocal(JsonObject args, ClientToolContext ctx) {
        LivingEntity anchor = ctx.anchor();
        if (anchor == null) {
            return "{\"success\":false,\"message\":\"perspective entity not available\"}";
        }
        var level = anchor.level();

        JsonObject root = new JsonObject();
        root.addProperty("dimension", level.dimension().identifier().toString());
        root.addProperty("game_time", level.getLevelData().getGameTime());
        root.addProperty("is_bright_outside", level.isBrightOutside());
        root.addProperty("is_dark_outside", level.isDarkOutside());

        String weather;
        if (level.isThundering()) weather = "thunder";
        else if (level.isRaining()) weather = "rain";
        else weather = "clear";
        root.addProperty("weather", weather);

        return root.toString();
    }
}
