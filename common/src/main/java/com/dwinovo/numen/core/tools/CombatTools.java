package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.core.task.HuntTaskRecord;
import com.dwinovo.numen.core.task.ShootTaskRecord;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Combat tools authored on the {@link NumenAction} surface — {@code hunt}
 * (melee) and {@code shoot} (ranged). Both are world-action dogfoods: they
 * return a {@link TaskRecord}, so the adapter ships it to the body and the task
 * queue runs it. Behaviour matches the hand-written tools they replace.
 */
public final class CombatTools {

    private static final int MAX_COUNT = 64;
    /** Combat is slower than mining; budget generously and scale with count. */
    private static final long TICKS_PER_KILL = 30 * 20;    // 30s each
    private static final long MIN_TIMEOUT_TICKS = 60 * 20; // 1 min floor

    // hunt radius bounds
    private static final int HUNT_DEFAULT_MAX_RADIUS = 48;
    private static final int HUNT_MAX_ALLOWED_RADIUS = 96;

    // shoot radius bounds
    private static final int SHOOT_DEFAULT_MAX_RADIUS = 64;
    private static final int SHOOT_MAX_ALLOWED_RADIUS = 128;

    public TaskRecord hunt(
List<String> entity_ids,
int count,
Integer radius,
            ToolContext ctx) {
        Set<EntityType<?>> targets = readEntityIdsHunt(entity_ids);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("entity_ids contained no valid entity type ids");
        }
        count = Math.max(1, Math.min(MAX_COUNT, count));

        int r = HUNT_DEFAULT_MAX_RADIUS;
        if (radius != null) {
            r = radius;
            if (r < 1) r = 1;
            if (r > HUNT_MAX_ALLOWED_RADIUS) r = HUNT_MAX_ALLOWED_RADIUS;
        }

        String label = labelFor(targets);
        long timeout = Math.max(MIN_TIMEOUT_TICKS, (long) count * TICKS_PER_KILL);
        long deadline = ctx.deadline(timeout);
        return new HuntTaskRecord(ctx.toolCallId(), deadline, targets, count, r, label);
    }

    public TaskRecord shoot(
List<String> entity_ids,
int count,
Integer radius,
            ToolContext ctx) {
        Set<EntityType<?>> targets = readEntityIdsShoot(entity_ids);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("entity_ids contained no valid entity type ids");
        }
        count = Math.max(1, Math.min(MAX_COUNT, count));

        int r = SHOOT_DEFAULT_MAX_RADIUS;
        if (radius != null) {
            r = radius;
            if (r < 1) r = 1;
            if (r > SHOOT_MAX_ALLOWED_RADIUS) r = SHOOT_MAX_ALLOWED_RADIUS;
        }

        String label = labelFor(targets);
        long timeout = Math.max(MIN_TIMEOUT_TICKS, (long) count * TICKS_PER_KILL);
        long deadline = ctx.deadline(timeout);
        return new ShootTaskRecord(ctx.toolCallId(), deadline, targets, count, r, label);
    }

    private static Set<EntityType<?>> readEntityIdsHunt(List<String> entity_ids) {
        Set<EntityType<?>> out = new LinkedHashSet<>();
        for (String el : entity_ids) {
            if (el == null) continue;
            ResourceLocation id = ResourceLocation.tryParse(el);
            if (id == null) continue;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            // getValue returns the PIG default for unknown ids in some registries;
            // guard by requiring the registry to actually contain the key.
            if (type != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                out.add(type);
            }
        }
        return out;
    }

    private static Set<EntityType<?>> readEntityIdsShoot(List<String> entity_ids) {
        Set<EntityType<?>> out = new LinkedHashSet<>();
        for (String el : entity_ids) {
            if (el == null) continue;
            ResourceLocation id = ResourceLocation.tryParse(el);
            if (id == null) continue;
            if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                out.add(BuiltInRegistries.ENTITY_TYPE.get(id));
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
}
