package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AgentRole;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ClientToolContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code scan_nearby_entities} tool — list every entity within a given
 * radius, optionally filtered by category. Returns id + type + position +
 * distance + HP + category so the LLM can pick a target for
 * {@code attack_target} or know who's around for tactical decisions.
 *
 * <h2>Truncation</h2>
 * Result list is sorted by distance (closest first) and capped at
 * {@link #MAX_RESULTS}. {@code truncated: true} signals to the LLM that
 * more entities exist beyond the cap — useful for "is this area swarmed?"
 * checks.
 *
 * <h2>Categories</h2>
 * <ul>
 *   <li>{@code player} — instance of vanilla {@code Player}</li>
 *   <li>{@code hostile} — instance of vanilla {@code Monster}
 *       (zombies, skeletons, blazes, endermen, etc.)</li>
 *   <li>{@code passive} — everything else (cows, items, projectiles,
 *       other Animus entities, the Animus entity's own owner, etc.)</li>
 * </ul>
 */
public final class ScanNearbyEntitiesTool implements AnimusTool {

    private static final int MAX_RESULTS = 20;
    private static final double MIN_RADIUS = 1.0;
    private static final double MAX_RADIUS = 64.0;

    @Override
    public String name() {
        return "scan_nearby_entities";
    }

    @Override
    public String description() {
        return "List entities within a radius around you, sorted by distance. "
                + "Use type_filter to narrow: 'hostile' for monsters, 'passive' "
                + "for animals/items, 'player' for players, 'all' for everything. "
                + "Returns at most 20 entities; truncated:true means more exist. "
                + "Each entry has id (use it with attack_target), type, position, "
                + "distance, hp, and category.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("radius", Map.of("type", "number",
                "description", "Search radius in blocks. Range [1, 64].",
                "minimum", MIN_RADIUS, "maximum", MAX_RADIUS));
        properties.put("type_filter", Map.of("type", "string",
                "description", "One of: hostile, passive, player, all.",
                "enum", List.of("hostile", "passive", "player", "all")));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("radius", "type_filter"));
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
    public Set<AgentRole> allowedRoles() {
        return EnumSet.of(AgentRole.PLAYER, AgentRole.ENTITY);
    }

    @Override
    public String executeLocal(JsonObject args, ClientToolContext ctx) {
        LivingEntity anchor = ctx.anchor();
        if (anchor == null) {
            return "{\"success\":false,\"message\":\"perspective entity not available\"}";
        }

        double radius = readDouble(args, "radius", MIN_RADIUS, MAX_RADIUS);
        String filter = readEnum(args, "type_filter",
                List.of("hostile", "passive", "player", "all"));

        AABB box = anchor.getBoundingBox().inflate(radius);
        List<Entity> raw = anchor.level().getEntities(anchor, box);

        List<ScoredEntity> matched = new ArrayList<>(raw.size());
        for (Entity e : raw) {
            String cat = categorise(e);
            if (!matches(filter, cat)) continue;
            matched.add(new ScoredEntity(e, cat, anchor.distanceTo(e)));
        }
        matched.sort(Comparator.comparingDouble(s -> s.distance));

        JsonArray entities = new JsonArray();
        int limit = Math.min(matched.size(), MAX_RESULTS);
        for (int i = 0; i < limit; i++) {
            ScoredEntity s = matched.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("id", s.entity.getId());
            o.addProperty("type", s.entity.getType().getDescriptionId());
            o.addProperty("category", s.category);
            JsonObject pos = new JsonObject();
            pos.addProperty("x", s.entity.getX());
            pos.addProperty("y", s.entity.getY());
            pos.addProperty("z", s.entity.getZ());
            o.add("position", pos);
            o.addProperty("distance", s.distance);
            if (s.entity instanceof LivingEntity le) {
                o.addProperty("hp", le.getHealth());
                o.addProperty("max_hp", le.getMaxHealth());
            }
            entities.add(o);
        }

        JsonObject root = new JsonObject();
        root.add("entities", entities);
        root.addProperty("total_found", matched.size());
        root.addProperty("truncated", matched.size() > MAX_RESULTS);
        root.addProperty("radius_searched", radius);
        root.addProperty("filter", filter);
        return root.toString();
    }

    private static String categorise(Entity e) {
        if (e instanceof Player) return "player";
        if (e instanceof Monster) return "hostile";
        return "passive";
    }

    private static boolean matches(String filter, String category) {
        if ("all".equals(filter)) return true;
        return filter.equals(category);
    }

    private record ScoredEntity(Entity entity, String category, double distance) {}

    private static double readDouble(JsonObject args, String key, double min, double max) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        double v;
        try {
            v = args.get(key).getAsDouble();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("argument '" + key + "' must be a number");
        }
        if (v < min) v = min;
        if (v > max) v = max;
        return v;
    }

    private static String readEnum(JsonObject args, String key, List<String> allowed) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        String v = args.get(key).getAsString();
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException(
                    "argument '" + key + "' must be one of " + allowed + ", got: " + v);
        }
        return v;
    }
}
