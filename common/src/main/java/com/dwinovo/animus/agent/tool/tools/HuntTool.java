package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.HuntTaskRecord;
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
 * The {@code hunt} tool — intent-level combat gathering, the {@code auto_mine}
 * of mobs. The LLM declares <em>what</em> mob type(s) to kill and <em>how
 * many</em>; the entity finds the nearest, chases it with the full terrain
 * pathfinder (bridging / digging / jumping), melees it to death, picks up drops
 * within reach, and repeats. No coordinates, no entity ids, no manual
 * scan/attack composition.
 *
 * <h2>Schema</h2>
 * <pre>
 * {
 *   "entity_ids": ["minecraft:zombie", "minecraft:husk"],
 *   "count": 5,
 *   "radius": 32          // optional, default auto-expands
 * }
 * </pre>
 *
 * <h2>Partial success</h2>
 * If fewer than {@code count} exist within range the task still succeeds with the
 * real {@code killed} number, so the LLM can decide whether to relocate.
 */
public final class HuntTool implements AnimusTool {

    private static final int DEFAULT_MAX_RADIUS = 48;
    private static final int MAX_ALLOWED_RADIUS = 96;
    private static final int MAX_COUNT = 64;
    /** Combat is slower than mining; budget generously and scale with count. */
    private static final long TICKS_PER_KILL = 30 * 20;    // 30s each
    private static final long MIN_TIMEOUT_TICKS = 60 * 20; // 1 min floor

    @Override
    public String name() {
        return HuntTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Hunt mobs by type and quantity. Give the entity id(s) and how many "
                + "to kill — the entity finds the nearest, chases it with the full "
                + "pathfinder (bridging gaps, digging through cover, jumping to "
                + "close in), melees it to death, picks up drops within reach, and "
                + "repeats until the count is met or none remain nearby. You do NOT "
                + "provide coordinates or entity ids — give TYPES (e.g. "
                + "minecraft:zombie). Optional radius caps how far to look (default "
                + "auto-expands). Returns the actual number killed, which may be "
                + "less if the area runs dry. Equip a weapon first (equip_item) for "
                + "faster kills; check get_self_status for your main hand. For "
                + "scattered drops afterward, use collect_items. If HP runs low "
                + "mid-fight you auto-eat from your inventory; the result reports "
                + "your post-fight HP and anything eaten.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("entity_ids", Map.of("type", "array",
                "description", "Namespaced entity type id(s) to hunt (e.g. minecraft:zombie).",
                "items", Map.of("type", "string"),
                "minItems", 1));
        properties.put("count", Map.of("type", "integer",
                "description", "How many to kill.",
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
        return new HuntTaskRecord(toolCallId, deadline, targets, count, radius, label);
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
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
            // getValue returns the PIG default for unknown ids in some registries;
            // guard by requiring the registry to actually contain the key.
            if (type != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                out.add(type);
            }
        }
        return out;
    }

    /** Short label: the first target's path (e.g. "zombie"), "+N" if more. */
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
