package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AgentRole;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ClientToolContext;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PlayerAgent perception tool — read the local player's own HP / hunger /
 * position / held item / dimension. The PlayerAgent's analogue of
 * EntityAgent's {@code get_self_status}: each agent gets a self-view tool
 * tuned to its perspective.
 */
public final class GetMyStatusTool implements AnimusTool {

    @Override
    public String name() { return "get_my_status"; }

    @Override
    public String description() {
        return "Read the player's own current status: HP / max HP, hunger / "
                + "saturation, position, dimension, main hand and off hand. "
                + "Use this to decide whether you can risk sending units far "
                + "from base (or whether you yourself need to eat / heal first). "
                + "No arguments.";
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
    public long defaultTimeoutTicks() { return 1; }

    @Override
    public boolean isLocal() { return true; }

    @Override
    public Set<AgentRole> allowedRoles() { return EnumSet.of(AgentRole.PLAYER); }

    @Override
    public String executeLocal(JsonObject args, ClientToolContext ctx) {
        Player p = ctx.player();
        if (p == null) {
            return "{\"success\":false,\"message\":\"local player not available\"}";
        }
        JsonObject root = new JsonObject();
        root.addProperty("name", p.getName().getString());
        root.addProperty("hp", p.getHealth());
        root.addProperty("max_hp", p.getMaxHealth());
        root.addProperty("hunger", p.getFoodData().getFoodLevel());
        root.addProperty("saturation", p.getFoodData().getSaturationLevel());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", p.getX());
        pos.addProperty("y", p.getY());
        pos.addProperty("z", p.getZ());
        root.add("position", pos);

        root.addProperty("dimension", p.level().dimension().identifier().toString());
        root.addProperty("main_hand", itemKey(p.getMainHandItem()));
        root.addProperty("off_hand", itemKey(p.getOffhandItem()));
        root.addProperty("on_ground", p.onGround());
        root.addProperty("in_water", p.isInWater());

        return root.toString();
    }

    private static String itemKey(ItemStack stack) {
        if (stack.isEmpty()) return "minecraft:air";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
