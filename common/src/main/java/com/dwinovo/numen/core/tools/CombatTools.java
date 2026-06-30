package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.api.Arg;
import com.dwinovo.numen.agent.tool.api.NumenAction;
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

    @NumenAction(name = "hunt", timeoutTicks = MIN_TIMEOUT_TICKS, description =
            "Hunt mobs by type and quantity. Give the entity id(s) and how many "
            + "to kill — the entity finds the nearest, chases it with the full "
            + "pathfinder (bridging gaps, digging through cover, jumping to "
            + "close in), and melees it to death, repeating until the count is "
            + "met or none remain nearby. It AUTO-SELECTS the strongest melee "
            + "weapon in its inventory before every swing (no need to equip_item "
            + "first — but it can only wield what it carries, so keep a good "
            + "sword/axe in its pack for real damage), and once the fight ends "
            + "it AUTO-COLLECTS the mob drops around the battlefield (loot ends "
            + "up in its pack — only reach for collect_items for drops flung far "
            + "away). You do NOT provide coordinates or entity ids — give TYPES "
            + "(e.g. minecraft:zombie). Optional radius caps how far to look "
            + "(default auto-expands). Returns the actual number killed, which "
            + "may be less if the area runs dry. If HP runs low mid-fight you "
            + "auto-eat from your inventory; the result reports your post-fight "
            + "HP and anything eaten.")
    public TaskRecord hunt(
            @Arg(value = "Namespaced entity type id(s) to hunt (e.g. minecraft:zombie).",
                    minItems = 1) List<String> entity_ids,
            @Arg(value = "How many to kill.", min = 1, max = MAX_COUNT) int count,
            @Arg(value = "Optional max search radius in blocks (default auto-expands to "
                    + HUNT_DEFAULT_MAX_RADIUS + ").",
                    min = 1, max = HUNT_MAX_ALLOWED_RADIUS, required = false) Integer radius,
            ToolContext ctx) {
        Set<EntityType<?>> targets = readEntityIdsHunt(entity_ids);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("entity_ids contained no valid entity type ids");
        }
        count = Math.clamp(count, 1, MAX_COUNT);

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

    @NumenAction(name = "shoot", timeoutTicks = MIN_TIMEOUT_TICKS, description =
            "Destroy entities at range with a ranged weapon — a bow or crossbow "
            + "(vanilla or modded). Give the entity id(s) and how many — the "
            + "companion finds the nearest, walks to within firing range and line "
            + "of sight (pathfinding around obstacles), and fires until each is "
            + "down, repeating until the count is met. Targets may be non-living: "
            + "use this for the Ender Dragon's end_crystal (which MUST be destroyed "
            + "at range) and for blazes. REQUIRES a bow or crossbow in your main "
            + "hand (equip_item) and matching ammo in your inventory — fails up "
            + "front if either is missing. Optional radius (default auto-expands). "
            + "Returns the actual number destroyed. Unlike hunt, shoot does NOT "
            + "pick up drops — ranged kills scatter their loot away from you, so "
            + "call collect_items afterward if you want it.")
    public TaskRecord shoot(
            @Arg(value = "Namespaced entity type id(s) to destroy (e.g. minecraft:end_crystal).",
                    minItems = 1) List<String> entity_ids,
            @Arg(value = "How many to destroy.", min = 1, max = MAX_COUNT) int count,
            @Arg(value = "Optional max search radius in blocks (default auto-expands to "
                    + SHOOT_DEFAULT_MAX_RADIUS + ").",
                    min = 1, max = SHOOT_MAX_ALLOWED_RADIUS, required = false) Integer radius,
            ToolContext ctx) {
        Set<EntityType<?>> targets = readEntityIdsShoot(entity_ids);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("entity_ids contained no valid entity type ids");
        }
        count = Math.clamp(count, 1, MAX_COUNT);

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
