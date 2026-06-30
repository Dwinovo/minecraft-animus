package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.task.TaskRecord;
import net.minecraft.world.level.block.Block;

import java.util.Set;

/**
 * Typed task descriptor for the intent-level {@code auto_mine} tool: "gather
 * {@code count} of these block types, search/pathfind/dig it yourself". The
 * goal ({@link MineBlockTaskGoal}) owns the whole loop — scan for the nearest
 * target, walk to it with the terrain-modifying pathfinder (bridging / digging
 * as needed), mine it into the entity inventory, repeat until the count is met
 * or the deposit runs dry within the radius.
 *
 * <p>The LLM never sees coordinates: it only declares <em>what</em> and
 * <em>how many</em> (and optionally how far to look). Drops/tool-tier follow
 * from whatever the entity holds, as in vanilla.
 */
public final class MineBlockTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "auto_mine";

    /** Block types to gather (include variants, e.g. iron_ore + deepslate_iron_ore). */
    public final Set<Block> targets;
    /** How many to gather before reporting success. */
    public final int count;
    /** Max spherical search radius (the goal auto-expands up to this). */
    public final int maxRadius;
    /** Human-readable target label for messages / debug overlay (e.g. "iron_ore"). */
    public final String label;

    /** Live progress = matching ITEMS gathered since the task started (Baritone-style inventory count,
     *  not blocks broken — multi-drop ores like redstone yield several items per block). Set each tick
     *  by the task; drives the stop condition + the debug overlay text. */
    private int mined = 0;

    public MineBlockTaskRecord(String toolCallId, long deadlineGameTime,
                               Set<Block> targets, int count, int maxRadius, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.targets = Set.copyOf(targets);
        this.count = count;
        this.maxRadius = maxRadius;
        this.label = label;
    }

    public int getMined() {
        return mined;
    }

    /** Set the running item-gathered tally (the task recomputes it from the inventory each tick). */
    public void setMined(int gathered) {
        this.mined = gathered;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + label + " " + mined + "/" + count;
    }
}
