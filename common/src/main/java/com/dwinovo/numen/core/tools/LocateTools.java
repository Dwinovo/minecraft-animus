package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.api.Arg;
import com.dwinovo.numen.agent.tool.api.NumenAction;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.core.task.LocateBiomeTaskRecord;
import com.dwinovo.numen.core.task.LocateStructureTaskRecord;

/**
 * Locate tools authored on the {@link NumenAction} surface — the LLM-facing
 * faces of vanilla's {@code /locate structure} and {@code /locate biome}. Both
 * are world-actions: they return a {@link TaskRecord} the body's task queue runs
 * across ticks. Behaviour matches the hand-written {@code NumenTool} classes.
 */
public final class LocateTools {

    private static final long TIMEOUT_TICKS = 30 * 20;
    private static final int MAX_ARG_LENGTH = 128;

    @NumenAction(name = "locate_structure", timeoutTicks = TIMEOUT_TICKS, description =
            "Find the nearest structure of a given type and get its "
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
            + "navigate by x/z and scan_blocks when you arrive.")
    public TaskRecord locateStructure(
            @Arg("Structure id (e.g. minecraft:fortress) or #tag (e.g. #minecraft:village).") String structure,
            ToolContext ctx) {
        structure = structure.trim();
        if (structure.isEmpty() || structure.length() > MAX_ARG_LENGTH) {
            throw new IllegalArgumentException("invalid structure argument");
        }
        return new LocateStructureTaskRecord(ctx.toolCallId(), ctx.deadline(TIMEOUT_TICKS), structure);
    }

    @NumenAction(name = "locate_biome", timeoutTicks = TIMEOUT_TICKS, description =
            "Find the nearest biome of a given type and get its coordinates, "
            + "compass direction and distance — no walking needed. `biome` is "
            + "a biome id — minecraft:warped_forest (endermen for pearls), "
            + "minecraft:soul_sand_valley, minecraft:desert, minecraft:plains, "
            + "minecraft:dark_forest — or a #tag for families like "
            + "#minecraft:is_forest or #minecraft:is_ocean. Searches YOUR "
            + "CURRENT dimension only, ~6400 blocks out. Biome edges are "
            + "fuzzy: the answer is accurate to ~64 blocks, so move_to the "
            + "x/z (pick a sensible y) and confirm with scan_blocks or "
            + "scan_nearby_entities when you arrive.")
    public TaskRecord locateBiome(
            @Arg("Biome id (e.g. minecraft:warped_forest) or #tag (e.g. #minecraft:is_forest).") String biome,
            ToolContext ctx) {
        biome = biome.trim();
        if (biome.isEmpty() || biome.length() > MAX_ARG_LENGTH) {
            throw new IllegalArgumentException("invalid biome argument");
        }
        return new LocateBiomeTaskRecord(ctx.toolCallId(), ctx.deadline(TIMEOUT_TICKS), biome);
    }
}
