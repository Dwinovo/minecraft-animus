package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.ShootTaskRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code shoot} tool — intent-level ranged combat with a bow, the ranged
 * sibling of {@code hunt}. The LLM declares <em>what</em> entity type(s) to
 * destroy and <em>how many</em>; the entity finds the nearest, closes to within
 * bow range + line of sight with the pathfinder, and looses arrows until it's
 * down, repeating until the count is met.
 *
 * <h2>Schema</h2>
 * <pre>
 * {
 *   "entity_ids": ["minecraft:end_crystal"],
 *   "count": 10,
 *   "radius": 48          // optional, default auto-expands
 * }
 * </pre>
 *
 * <h2>Why it exists</h2>
 * Some targets <em>must</em> be killed at range: the Ender Dragon's end crystals
 * (non-living entities you can't melee), blazes (ranged attackers best fought
 * from afar). Requires a bow held in the main hand and arrows in the inventory —
 * the task fails up front with guidance if either is missing.
 */
public final class ShootTool implements AnimusTool {

    private static final int DEFAULT_MAX_RADIUS = 64;
    private static final int MAX_ALLOWED_RADIUS = 128;
    private static final int MAX_COUNT = 64;
    private static final long TICKS_PER_KILL = 30 * 20;    // 30s each
    private static final long MIN_TIMEOUT_TICKS = 60 * 20; // 1 min floor

    @Override
    public String name() {
        return ShootTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Destroy entities at range with a bow. Give the entity id(s) and how "
                + "many — the entity finds the nearest, walks to within bow range "
                + "and line of sight (pathfinding around obstacles), and fires "
                + "arrows until each is down, repeating until the count is met. "
                + "Targets may be non-living: use this for the Ender Dragon's "
                + "end_crystal (which MUST be destroyed at range) and for blazes. "
                + "REQUIRES a bow held in your main hand (equip_item) and arrows in "
                + "your inventory — fails up front if either is missing. Optional "
                + "radius (default auto-expands). Returns the actual number destroyed.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("entity_ids", Map.of("type", "array",
                "description", "Namespaced entity type id(s) to destroy (e.g. minecraft:end_crystal).",
                "items", Map.of("type", "string"),
                "minItems", 1));
        properties.put("count", Map.of("type", "integer",
                "description", "How many to destroy.",
                "minimum", 1, "maximum", MAX_COUNT));
        properties.put("radius", Map.of("type", "integer",
                "description", "Optional max search radius in blocks (default auto-expands to "
                        + DEFAULT_MAX_RADIUS + ").",
                "minimum", 1, "maximum", MAX_ALLOWED_RADIUS));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("entity_ids", "count"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return MIN_TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        Set<EntityType<?>> targets = readEntityIds(args);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("entity_ids contained no valid entity type ids");
        }
        int count = requireInt(args, "count");
        if (count < 1) count = 1;
        if (count > MAX_COUNT) count = MAX_COUNT;

        int radius = DEFAULT_MAX_RADIUS;
        if (args.has("radius") && !args.get("radius").isJsonNull()) {
            radius = args.get("radius").getAsInt();
            if (radius < 1) radius = 1;
            if (radius > MAX_ALLOWED_RADIUS) radius = MAX_ALLOWED_RADIUS;
        }

        String label = labelFor(targets);
        long timeout = Math.max(MIN_TIMEOUT_TICKS, (long) count * TICKS_PER_KILL);
        long deadline = currentGameTime + timeout;
        return new ShootTaskRecord(toolCallId, deadline, targets, count, radius, label);
    }

    private static Set<EntityType<?>> readEntityIds(JsonObject args) {
        if (!args.has("entity_ids") || !args.get("entity_ids").isJsonArray()) {
            throw new IllegalArgumentException("entity_ids must be a non-empty array");
        }
        JsonArray arr = args.getAsJsonArray("entity_ids");
        Set<EntityType<?>> out = new LinkedHashSet<>();
        for (JsonElement el : arr) {
            if (el == null || el.isJsonNull()) continue;
            Identifier id = Identifier.tryParse(el.getAsString());
            if (id == null) continue;
            if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                out.add(BuiltInRegistries.ENTITY_TYPE.getValue(id));
            }
        }
        return out;
    }

    private static String labelFor(Set<EntityType<?>> targets) {
        EntityType<?> first = targets.iterator().next();
        String path = BuiltInRegistries.ENTITY_TYPE.getKey(first).getPath();
        return targets.size() == 1 ? path : path + "+" + (targets.size() - 1);
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
