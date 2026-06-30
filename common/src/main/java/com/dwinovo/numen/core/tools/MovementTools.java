package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.api.Arg;
import com.dwinovo.numen.agent.tool.api.NumenAction;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.core.task.MoveToTaskRecord;

/**
 * Movement tools authored on the {@link NumenAction} surface. {@code move_to}
 * is the first world-action dogfood: it returns a {@link TaskRecord}, so the
 * adapter ships it to the body and the task queue runs it — the method's only
 * job is to validate args and build the record (the {@link ToolContext} carries
 * the call id and deadline basis). Behaviour matches the hand-written tool.
 */
public final class MovementTools {

    /** Base budget: 30 seconds at vanilla 20 tps (the goal extends it by distance at runtime). */
    private static final long DEFAULT_TIMEOUT_TICKS = 30 * 20;
    private static final double MIN_SPEED = 0.1;
    private static final double MAX_SPEED = 2.0;

    @NumenAction(name = "move_to", timeoutTicks = DEFAULT_TIMEOUT_TICKS, description =
            "Travel somewhere — full terrain-traversing navigation, not just "
            + "walking. Pick ONE of three intents by which coordinates you "
            + "fill (leave the others null):\n"
            + "• Go to a LOCATION: give x and z, leave y null. The companion "
            + "walks to that spot and stands on whatever ground is there — Y is "
            + "auto-resolved to the surface. THIS IS THE DEFAULT for 'go over "
            + "there' / following / exploring; never guess a Y for a location.\n"
            + "• Go to an EXACT cell: give x, y and z. Only for a specific cell "
            + "you know is reachable (e.g. a block you scanned). If that cell is "
            + "mid-air or walled in it will report it couldn't reach it.\n"
            + "• Change ELEVATION: give y only (x and z null) to climb to the "
            + "surface or descend to a mining depth at your current column.\n"
            + "En route it mines through obstructions, digs down/up, bridges "
            + "gaps and pillars up with cobblestone/dirt from inventory. Digging "
            + "is gated by your HELD tool: stone/deepslate need a pickaxe IN "
            + "HAND (equip_item first); a sword held makes stone an impassable "
            + "wall. Consumes scaffold blocks and tool durability; carry "
            + "cobblestone/dirt for gaps. Timeout scales with distance; the "
            + "result reports the actual position reached (and the real ground "
            + "height) — call again with the same target to resume. But if it "
            + "reports NO path or stops far short, that spot is unreachable or too "
            + "far: pick a NEARER waypoint, or scan first — don't just repeat the "
            + "same unreachable target. move_to is for getting somewhere to STAND; "
            + "to open/use a station give its coordinate to interact_at instead.")
    public TaskRecord moveTo(
            @Arg(value = "Target X. Null for an elevation-only move (y alone).", nullable = true) Double x,
            @Arg(value = "Target Y (block height). LEAVE NULL to go to a "
                    + "location (x+z) — Y is auto-resolved to the surface. Only "
                    + "set it for an exact cell (x+y+z) or an elevation move (y alone).",
                    nullable = true) Double y,
            @Arg(value = "Target Z. Null for an elevation-only move (y alone).", nullable = true) Double z,
            @Arg(value = "Speed multiplier in [0.1, 2.0]. 1.0 is normal walking speed.",
                    min = MIN_SPEED, max = MAX_SPEED) double speed,
            ToolContext ctx) {
        if (speed < MIN_SPEED) speed = MIN_SPEED;
        if (speed > MAX_SPEED) speed = MAX_SPEED;
        // MoveToTaskRecord validates the x/y/z combination and throws a teaching
        // error for an ambiguous one (e.g. only x given).
        return new MoveToTaskRecord(ctx.toolCallId(), ctx.deadline(DEFAULT_TIMEOUT_TICKS), x, y, z, speed);
    }
}
