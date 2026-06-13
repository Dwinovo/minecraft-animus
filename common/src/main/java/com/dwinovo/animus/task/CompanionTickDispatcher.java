package com.dwinovo.animus.task;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.network.payload.TaskResultPayload;
import com.dwinovo.animus.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-tick driver of companion tasks — the player-body replacement for the
 * Mob's {@code GoalSelector} + {@code customServerAiStep}. Each tick, for every
 * live {@link AnimusPlayer}: pull the head of its task queue, run the matching
 * {@link CompanionTask} to completion (deadline-bounded), and ship finished
 * results back to the owner as {@link TaskResultPayload}. Registered from both
 * loaders' end-of-tick hooks.
 */
public final class CompanionTickDispatcher {

    private record Running(CompanionTask task, TaskRecord record) {}

    private static final Map<UUID, Running> ACTIVE = new HashMap<>();

    private CompanionTickDispatcher() {}

    public static void tick(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof AnimusPlayer ap) {
                tickOne(ap);
            }
        }
    }

    private static void tickOne(AnimusPlayer player) {
        UUID id = player.getUUID();
        Running running = ACTIVE.get(id);

        if (running == null) {
            TaskRecord rec = player.getTaskQueue().pollHead();
            if (rec != null) {
                rec.setState(TaskState.RUNNING);
                player.setActiveTask(rec);
                player.setDebugTask(rec.describe());
                player.pathTally().reset();
                CompanionTask task = CompanionTaskFactory.create(player, rec);
                running = new Running(task, rec);
                ACTIVE.put(id, running);
                task.start();   // may flip the record terminal immediately
            }
        } else if (running.record().getState() == TaskState.RUNNING) {
            if (player.level().getGameTime() >= running.record().getDeadlineGameTime()) {
                running.record().setState(TaskState.TIMEOUT);
            } else {
                running.record().setState(running.task().tick());
            }
        }

        // Finish on any terminal state (set by start(), tick(), deadline, or cancel).
        running = ACTIVE.get(id);
        if (running != null) {
            TaskState st = running.record().getState();
            if (st != TaskState.RUNNING && st != TaskState.PENDING) {
                running.record().setResult(running.task().buildResult(st));
                player.getTaskQueue().complete(running.record());
                player.setActiveTask(null);
                player.setDebugTask(null);
                ACTIVE.remove(id);
            }
        }

        drainResults(player);
    }

    private static void drainResults(AnimusPlayer player) {
        List<TaskRecord> completed = player.getTaskQueue().drainCompleted();
        if (completed.isEmpty()) return;
        ServerPlayer owner = player.resolveOwnerPlayer();
        if (owner == null) return;   // owner offline — drop (the loop will re-ask)
        for (TaskRecord rec : completed) {
            TaskResult result = rec.getResult();
            String json = result == null
                    ? "{\"success\":false,\"message\":\"no result produced\"}"
                    : result.toJson();
            Services.NETWORK.sendToPlayer(owner,
                    new TaskResultPayload(player.getUUID(), rec.getToolCallId(), json));
        }
    }
}
