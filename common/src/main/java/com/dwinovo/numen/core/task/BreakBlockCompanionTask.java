package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.Interaction;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.task.CompanionTask;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.task.TaskState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code break_block} on the player body: walk within reach of one exact cell and
 * break it as a left-click ({@link Interaction#attackBlock}) — the same native
 * timed break the pathfinder/auto-mine use (creative insta, survival by real
 * hardness, best tool auto-selected). Player-body twin of BreakBlockTaskGoal
 * (construction surgery — clear a frame cell, undo a misplace).
 */
public final class BreakBlockCompanionTask implements CompanionTask {

    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double WALK_SPEED = 1.0;

    private final NumenPlayer player;
    private final BreakBlockTaskRecord r;
    private PlayerNav nav;
    private Interaction breaking;
    private String brokenBlock = "?";
    private String doneReason = "done";

    public BreakBlockCompanionTask(NumenPlayer player, BreakBlockTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        var state = player.level().getBlockState(r.target);
        brokenBlock = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (state.isAir()) {
            doneReason = "nothing to break at " + posLabel() + " — it's already air";
            r.setState(TaskState.FAILED);
            return;
        }
        String hazard = BlockMiningProgress.fluidBreakHazard(player.level(), r.target);
        if (hazard != null) {
            doneReason = hazard;
            r.setState(TaskState.FAILED);
            return;
        }
        // Fail fast instead of grinding a block the current tools can't HARVEST: breaking a
        // requiresCorrectToolForDrops block (stone, ore, …) bare-handed destroys it for no drop
        // (or, for hard blocks, just times out). Same gate the pathfinder/auto-mine cost model
        // uses (COST_INF) — teach the model to equip a tool rather than waste the block.
        if (!BlockHelper.canHarvest(player.getInventory(), state)) {
            doneReason = "can't usefully break " + brokenBlock + " at " + posLabel()
                    + " — the hotbar has no tool that harvests it, so breaking it would destroy it"
                    + " without any drop. Equip the right tool (e.g. a pickaxe) to the hotbar first.";
            r.setState(TaskState.FAILED);
            return;
        }
        nav = new PlayerNav(player, r.target, WALK_SPEED, this::withinReach);
    }

    @Override
    public TaskState tick() {
        if (withinReach()) {
            if (player.level().getBlockState(r.target).isAir()) {
                doneReason = "the block at " + posLabel() + " is already gone";
                return TaskState.SUCCESS;
            }
            if (breaking == null) breaking = Interaction.attackBlock(player, r.target);
            return switch (breaking.tick()) {
                case DONE -> {
                    doneReason = "broke " + brokenBlock + " at " + posLabel();
                    yield TaskState.SUCCESS;
                }
                case FAILED -> {
                    doneReason = "couldn't break " + posLabel() + ": " + breaking.failReason();
                    yield TaskState.FAILED;
                }
                case RUNNING -> TaskState.RUNNING;   // mid-break (survival hardness timing)
            };
        }
        if (nav == null) return TaskState.FAILED;
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> TaskState.RUNNING;   // mine next tick
            case FAILED -> {
                doneReason = "can't reach " + posLabel() + ": " + nav.failReason();
                yield TaskState.FAILED;
            }
        };
    }

    private boolean withinReach() {
        return player.onGround()
                && player.distanceToSqr(Vec3.atCenterOf(r.target)) <= REACH_SQR;
    }

    private String posLabel() {
        return r.target.getX() + "," + r.target.getY() + "," + r.target.getZ();
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (nav != null) nav.stop();
        if (breaking != null) breaking.stop();
        Map<String, Object> data = new HashMap<>();
        data.put("x", r.target.getX());
        data.put("y", r.target.getY());
        data.put("z", r.target.getZ());
        data.put("block", brokenBlock);
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, data);
            case TIMEOUT -> TaskResult.timeout("timed out before breaking " + posLabel());
            case CANCELLED -> TaskResult.cancelled("break_block interrupted");
            default -> TaskResult.fail(doneReason, data);
        };
    }
}
