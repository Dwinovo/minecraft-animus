package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.net.TaskResultPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * numen-core's server-tick driver of companion tasks. The engine ({@code numen-api})
 * owns the body and is a pure scheduler; <em>task execution</em> is core's, so the
 * per-companion task queue lives here (keyed by companion UUID), not on the body.
 *
 * <p>Each tick, for every live {@link NumenPlayer}: pull the head of its queue,
 * run the matching {@link CompanionTask} to completion (deadline-bounded), and
 * ship finished results back to the owner as {@link TaskResultPayload} — core's
 * own packet. Registered from core's end-of-tick hooks; finalised on body
 * removal / death / owner-abort via the engine's {@code CompanionLifecycle} seam.
 */
@com.dwinovo.numen.api.Internal
public final class CompanionTickDispatcher {

    private record Running(CompanionTask task, TaskRecord record) {}

    private static final Map<UUID, Running> ACTIVE = new HashMap<>();
    /** Per-companion task queue — replaces the body-hosted queue the engine no longer keeps. */
    private static final Map<UUID, TaskQueue> QUEUES = new HashMap<>();

    private CompanionTickDispatcher() {}

    /** The companion's task queue (created on first use). Body-bound tools enqueue here. */
    public static TaskQueue queueFor(UUID companionUuid) {
        return QUEUES.computeIfAbsent(companionUuid, k -> new TaskQueue());
    }

    public static void tick(MinecraftServer server) {
        com.dwinovo.numen.entity.Companions.tickRespawns(server);   // timed death recoveries
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer ap) {
                tickOne(ap);
            }
        }
    }

    /**
     * Drop a companion's running task WITHOUT shipping a result — used on death, where the client's
     * {@code NumenDeathPayload} already resolves the in-flight tool call with the death cause (so a
     * second result here would be a duplicate the client ignores).
     */
    public static void clearActiveTask(NumenPlayer player) {
        ACTIVE.remove(player.getUUID());
    }

    /** Owner pressed Stop: cancel the running task and drop the queue for this companion. */
    public static void cancelFor(NumenPlayer player) {
        UUID id = player.getUUID();
        queueFor(id).cancelAll("interrupted by owner");
        Running running = ACTIVE.get(id);
        if (running != null && running.record().getState() == TaskState.RUNNING) {
            running.record().setState(TaskState.CANCELLED);
        }
    }

    /**
     * Finalize a companion's running task because the BODY is leaving the world
     * (dormancy / dismissal / death) — the tick loop only visits players still in
     * the player list, so without this the running task is orphaned in {@link
     * #ACTIVE} and its {@code buildResult} side-effects never run (e.g. a mining
     * dig's crack overlay would stay painted on every viewer until chunk reload).
     */
    public static void onCompanionRemoved(NumenPlayer player) {
        UUID id = player.getUUID();
        Running running = ACTIVE.remove(id);
        if (running != null) {
            TaskState st = running.record().getState();
            if (st == TaskState.RUNNING || st == TaskState.PENDING) {
                st = TaskState.CANCELLED;
                running.record().setState(st);
            }
            running.record().setResult(running.task().buildResult(st));
            queueFor(id).complete(running.record());
            drainResults(player);
        }
        QUEUES.remove(id);   // the body is gone; don't leak its queue
    }

    private static void tickOne(NumenPlayer player) {
        UUID id = player.getUUID();
        Running running = ACTIVE.get(id);

        if (running == null) {
            TaskRecord rec = queueFor(id).pollHead();
            if (rec != null) {
                rec.setState(TaskState.RUNNING);
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
                queueFor(id).complete(running.record());
                ACTIVE.remove(id);
            }
        }

        drainResults(player);
    }

    private static void drainResults(NumenPlayer player) {
        List<TaskRecord> completed = queueFor(player.getUUID()).drainCompleted();
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
