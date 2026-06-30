package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.PlaceManeuver;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.CompanionTask;
import com.dwinovo.numen.core.task.PlayerInv;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.task.TaskState;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code place_block} on the player body: walk within reach (full pathfinding —
 * digs / bridges / climbs to get there), then place like a real player with the
 * shared {@link PlaceManeuver} "edge sneak" — hold sneak, edge to the block's rim
 * so the support face comes into view, look at it, and place natively.
 */
public final class PlaceBlockCompanionTask implements CompanionTask {

    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double WALK_SPEED = 1.0;

    private final NumenPlayer player;
    private final PlaceBlockTaskRecord r;
    private PlayerNav nav;
    private PlaceManeuver maneuver;
    private String doneReason = "done";

    public PlaceBlockCompanionTask(NumenPlayer player, PlaceBlockTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        var level = player.level();
        if (PlayerInv.count(player.getInventory(), r.item) <= 0) {
            fail("no " + r.label + " in inventory to place");
            return;
        }
        // Occupancy is the one thing worth a fast, clear message; everything else (support faces,
        // modded placement rules) is left to vanilla's own placement to accept or reject — we don't
        // second-guess it with our own heuristic. Vanilla's `canBeReplaced` is the same test it uses.
        BlockState existing = level.getBlockState(r.pos);
        if (!existing.isAir() && !existing.canBeReplaced()) {
            fail("target " + coords() + " is already occupied by "
                    + BuiltInRegistries.BLOCK.getKey(existing.getBlock()).getPath());
            return;
        }
        nav = new PlayerNav(player, r.pos, WALK_SPEED, this::withinReach);
    }

    @Override
    public TaskState tick() {
        if (player.level().getBlockState(r.pos).is(r.block)) {
            doneReason = "placed " + r.label + " at " + coords() + orientation();
            return TaskState.SUCCESS;
        }
        if (withinReach()) {
            if (maneuver == null) {
                maneuver = new PlaceManeuver(player, r.pos,
                        () -> PlayerInv.findSlot(player.getInventory(), r.item),
                        () -> player.level().getBlockState(r.pos).is(r.block),
                        new PlaceManeuver.Hints(r.facing, r.axis, r.topHalf), r.block);
            }
            return switch (maneuver.tick()) {
                case DONE -> {
                    doneReason = "placed " + r.label + " at " + coords() + orientation();
                    yield TaskState.SUCCESS;
                }
                case FAILED -> {
                    doneReason = maneuver.failReason();
                    yield TaskState.FAILED;
                }
                case RUNNING -> TaskState.RUNNING;
            };
        }
        if (nav == null) return TaskState.FAILED;
        return switch (nav.tick()) {
            case RUNNING, ARRIVED -> TaskState.RUNNING;
            case FAILED -> {
                doneReason = "can't reach a spot to place at " + coords() + " (" + nav.failReason() + ")";
                yield TaskState.FAILED;
            }
        };
    }

    private boolean withinReach() {
        return player.onGround() && player.distanceToSqr(Vec3.atCenterOf(r.pos)) <= REACH_SQR;
    }

    private String coords() {
        return r.pos.getX() + "," + r.pos.getY() + "," + r.pos.getZ();
    }

    /** Report the ACTUAL orientation the block landed in (so the model can see + correct it), flagging
     *  any property that didn't come out the way it asked. Empty for blocks with no orientation. */
    private String orientation() {
        BlockState s = player.level().getBlockState(r.pos);
        List<String> parts = new ArrayList<>();
        Direction f = s.hasProperty(BlockStateProperties.FACING) ? s.getValue(BlockStateProperties.FACING)
                : s.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? s.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : null;
        if (f != null) parts.add("facing " + f.getName() + mismatch(r.facing != null && r.facing != f, r.facing));
        Direction.Axis ax = s.hasProperty(BlockStateProperties.AXIS) ? s.getValue(BlockStateProperties.AXIS)
                : s.hasProperty(BlockStateProperties.HORIZONTAL_AXIS) ? s.getValue(BlockStateProperties.HORIZONTAL_AXIS)
                : null;
        if (ax != null) parts.add("axis " + ax.getName() + mismatch(r.axis != null && r.axis != ax, r.axis));
        Boolean top = topHalf(s);
        if (top != null) {
            parts.add((top ? "top" : "bottom") + " half"
                    + mismatch(r.topHalf != null && !r.topHalf.equals(top), r.topHalf == null ? null : (r.topHalf ? "top" : "bottom")));
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    private static Boolean topHalf(BlockState s) {
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType t = s.getValue(BlockStateProperties.SLAB_TYPE);
            return t == SlabType.DOUBLE ? null : t == SlabType.TOP;
        }
        if (s.hasProperty(BlockStateProperties.HALF)) {
            return s.getValue(BlockStateProperties.HALF) == Half.TOP;
        }
        return null;
    }

    private static String mismatch(boolean differs, Object wanted) {
        return differs ? " [wanted " + wanted + "]" : "";
    }

    private void fail(String reason) {
        doneReason = reason;
        r.setState(TaskState.FAILED);
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (nav != null) nav.stop();
        if (maneuver != null) maneuver.stop();
        Map<String, Object> data = new HashMap<>();
        data.put("block", r.label);
        data.put("x", r.pos.getX());
        data.put("y", r.pos.getY());
        data.put("z", r.pos.getZ());
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case TIMEOUT -> TaskResult.timeout("timed out before placing " + r.label + " at " + coords());
            case CANCELLED -> TaskResult.cancelled("place_block interrupted");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
