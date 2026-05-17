package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ClientToolContext;
import com.dwinovo.animus.entity.AnimusEntity;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code get_self_status} tool — read the entity's own HP, position,
 * held items, current attack target. Always call this before any combat
 * or risky decision; the LLM otherwise has no idea of its own health.
 *
 * <h2>Why this is local</h2>
 * The Animus entity is rendered on the client, so its synced fields (HP,
 * position, equipment) are all immediately readable without a server
 * round-trip. Zero latency, zero token cost beyond the tool call itself.
 */
public final class GetSelfStatusTool implements AnimusTool {

    @Override
    public String name() {
        return "get_self_status";
    }

    @Override
    public String description() {
        return "Read your own current status: HP / max HP, position, dimension, "
                + "main hand and off hand items, current attack target, and "
                + "movement state. ALWAYS call this before combat decisions "
                + "(attack_target, retreat, eat) and periodically during long "
                + "tasks. No arguments.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        schema.put("required", List.of());
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return 1;  // local, ignored
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public String executeLocal(JsonObject args, ClientToolContext ctx) {
        AnimusEntity entity = ctx.entity();
        if (entity == null) {
            return "{\"success\":false,\"message\":\"entity not loaded on client\"}";
        }

        JsonObject root = new JsonObject();
        root.addProperty("entity_id", entity.getId());
        root.addProperty("hp", entity.getHealth());
        root.addProperty("max_hp", entity.getMaxHealth());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", entity.getX());
        pos.addProperty("y", entity.getY());
        pos.addProperty("z", entity.getZ());
        root.add("position", pos);

        root.addProperty("dimension", entity.level().dimension().identifier().toString());
        root.addProperty("main_hand", itemKey(entity.getMainHandItem()));
        root.addProperty("off_hand", itemKey(entity.getOffhandItem()));

        LivingEntity tgt = entity.getTarget();
        if (tgt != null) {
            JsonObject t = new JsonObject();
            t.addProperty("entity_id", tgt.getId());
            t.addProperty("type", tgt.getType().getDescriptionId());
            t.addProperty("hp", tgt.getHealth());
            root.add("target", t);
        } else {
            root.add("target", JsonNull.INSTANCE);
        }

        root.addProperty("on_ground", entity.onGround());
        root.addProperty("in_water", entity.isInWater());
        root.addProperty("in_lava", entity.isInLava());

        return root.toString();
    }

    private static String itemKey(ItemStack stack) {
        if (stack.isEmpty()) return "minecraft:air";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
