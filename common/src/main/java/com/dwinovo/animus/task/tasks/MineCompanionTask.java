package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.pathing.exec.PlayerNav;
import com.dwinovo.animus.pathing.util.BlockScanner;
import com.dwinovo.animus.task.CompanionTask;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code auto_mine} on the companion player body: scan for the nearest target
 * block, walk within reach via {@link PlayerNav}, break it with the player's own
 * survival break ({@code gameMode.destroyBlock} — drops fall and are picked up
 * natively), repeat until the count is met or the radius is exhausted. A first
 * cut of {@code MineBlockTaskGoal} for the player body; the economics/scaffold-
 * ledger refinements port later.
 */
public final class MineCompanionTask implements CompanionTask {

    private static final int INITIAL_RADIUS = 16;
    private static final int RADIUS_STEP = 16;
    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double MINE_SPEED = 1.0;

    private final AnimusPlayer player;
    private final MineBlockTaskRecord r;
    private final Set<BlockPos> skipped = new HashSet<>();

    private int currentRadius;
    private BlockPos target;
    private PlayerNav nav;
    private String doneReason = "done";

    public MineCompanionTask(AnimusPlayer player, MineBlockTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        currentRadius = Math.min(INITIAL_RADIUS, r.maxRadius);
    }

    @Override
    public TaskState tick() {
        if (r.getMined() >= r.count) {
            doneReason = "mined all requested";
            return TaskState.SUCCESS;
        }
        Level level = player.level();

        // Approaching a chosen target.
        if (nav != null) {
            switch (nav.tick()) {
                case RUNNING -> { return TaskState.RUNNING; }
                case ARRIVED -> { nav = null; /* fall through to mine below */ }
                case FAILED -> {
                    if (target != null) skipped.add(target.immutable());
                    nav = null;
                    target = null;
                    return TaskState.RUNNING;
                }
            }
        }

        // Have a target in reach → break it.
        if (target != null) {
            if (level.getBlockState(target).isAir()) {   // already gone
                target = null;
                return TaskState.RUNNING;
            }
            if (withinReach(target)) {
                com.dwinovo.animus.pathing.exec.InputDriver.lookAt(player, Vec3.atCenterOf(target));
                player.gameMode.destroyBlock(target);
                if (level.getBlockState(target).isAir()) {
                    r.incrementMined();
                }
                target = null;
                return TaskState.RUNNING;
            }
            // Drifted out of reach — re-approach.
            nav = new PlayerNav(player, target, MINE_SPEED, () -> withinReach(target));
            return TaskState.RUNNING;
        }

        // SCAN for the next target.
        BlockScanner.Hit hit = nearest(level);
        if (hit == null) {
            if (currentRadius < r.maxRadius) {
                currentRadius = Math.min(currentRadius + RADIUS_STEP, r.maxRadius);
                return TaskState.RUNNING;
            }
            doneReason = r.getMined() > 0
                    ? "only " + r.getMined() + "/" + r.count + " found within " + r.maxRadius + " blocks"
                    : "no reachable " + r.label + " found within " + r.maxRadius + " blocks";
            return r.getMined() > 0 ? TaskState.SUCCESS : TaskState.FAILED;
        }
        target = hit.pos();
        if (withinReach(target)) {
            return TaskState.RUNNING;   // mine it next tick
        }
        nav = new PlayerNav(player, target, MINE_SPEED, () -> withinReach(target));
        return TaskState.RUNNING;
    }

    private BlockScanner.Hit nearest(Level level) {
        BlockPos center = player.blockPosition();
        for (BlockScanner.Hit hit : BlockScanner.findWithin(level, center, currentRadius, r.targets)) {
            if (skipped.contains(hit.pos())) continue;
            if (BlockMiningProgress.fluidBreakHazard(level, hit.pos()) != null) continue;
            return hit;
        }
        return null;
    }

    private boolean withinReach(BlockPos pos) {
        return player.onGround()
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= REACH_SQR;
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (nav != null) nav.stop();
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("mined", r.getMined());
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(
                    "mined " + r.getMined() + "/" + r.count + " " + r.label + " (" + doneReason + ")", data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out after mining " + r.getMined() + "/" + r.count + " " + r.label, true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "interrupted after mining " + r.getMined() + "/" + r.count + " " + r.label, false, true, data);
            case FAILED -> TaskResult.fail(doneReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
