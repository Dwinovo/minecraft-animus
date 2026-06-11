package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.tasks.LocateStructureTaskRecord;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code locate_structure} tool — the LLM-facing face of vanilla's
 * {@code /locate structure}. Generalizes the old stronghold-only locator:
 * wandering cardinal directions hoping to stumble onto a Nether fortress was
 * the single biggest time sink in the dragon route's Nether phase.
 */
public final class LocateStructureTool implements AnimusTool {

    private static final long TIMEOUT_TICKS = 30 * 20;
    private static final int MAX_ARG_LENGTH = 128;

    @Override
    public String name() {
        return LocateStructureTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Find the nearest structure of a given type and get its "
                + "coordinates, compass direction and distance. `structure` is a "
                + "structure id — minecraft:stronghold, minecraft:fortress, "
                + "minecraft:bastion_remnant, minecraft:ancient_city, "
                + "minecraft:end_city, minecraft:monument, minecraft:mansion, "
                + "minecraft:pillager_outpost — or a #tag for families like "
                + "#minecraft:village or #minecraft:ruined_portal. Searches YOUR "
                + "CURRENT dimension only: fortresses/bastions exist in the "
                + "Nether, end cities in the End. For the stronghold this is the "
                + "eye-free equivalent of throwing eyes of ender — save the eyes "
                + "for the 12 portal frames. The returned y is approximate; "
                + "navigate by x/z and scan_blocks when you arrive.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("structure", Map.of("type", "string",
                "description", "Structure id (e.g. minecraft:fortress) or #tag (e.g. #minecraft:village)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("structure"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return TIMEOUT_TICKS;
    }

    @Override
    public TaskRecord toTaskRecord(String toolCallId, JsonObject args, long currentGameTime) {
        if (!args.has("structure") || args.get("structure").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: structure");
        }
        String structure = args.get("structure").getAsString().trim();
        if (structure.isEmpty() || structure.length() > MAX_ARG_LENGTH) {
            throw new IllegalArgumentException("invalid structure argument");
        }
        return new LocateStructureTaskRecord(toolCallId, currentGameTime + TIMEOUT_TICKS, structure);
    }
}
