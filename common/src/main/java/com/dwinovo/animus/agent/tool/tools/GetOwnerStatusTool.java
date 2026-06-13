package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code get_owner_status} tool — read the owning player's HP / hunger /
 * position / held item / distance. Critical for "stay close" / "protect
 * owner" / "follow" decisions.
 *
 * <p>Returns {@code online: false} when the owner is offline. Runs as a
 * server query, so it sees the owner anywhere — any distance, any dimension.
 */
public final class GetOwnerStatusTool implements AnimusTool {

    @Override
    public String name() {
        return "get_owner_status";
    }

    @Override
    public String description() {
        return "Read your owner's current status: name, online state, HP, "
                + "hunger, position, distance from you, and held item. Call "
                + "before any 'follow', 'protect', or 'rendezvous' decision. "
                + "If the owner is offline the call returns online:false — "
                + "default to autonomous mode until they return. No arguments.";
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
        return 1;
    }

    @Override
    public boolean isQuery() {
        return true;
    }

    @Override
    public String executeQuery(JsonObject args, AnimusPlayer entity) {
        JsonObject root = new JsonObject();
        java.util.UUID ownerUuid = entity.getOwnerUuid();
        if (ownerUuid == null) {
            root.addProperty("online", false);
            root.addProperty("message", "no owner (untamed)");
            return root.toString();
        }
        root.addProperty("owner_uuid", ownerUuid.toString());

        // Server-wide resolution: vanilla getOwner() is scoped to the PET's
        // level and would report a cross-dimension owner as "offline".
        Player player = entity.resolveOwnerPlayer();
        if (player == null) {
            root.addProperty("online", false);
            root.addProperty("message", "owner offline");
            return root.toString();
        }

        root.addProperty("online", true);
        root.addProperty("name", player.getName().getString());
        root.addProperty("hp", player.getHealth());
        root.addProperty("max_hp", player.getMaxHealth());
        root.addProperty("hunger", player.getFoodData().getFoodLevel());
        root.addProperty("saturation", player.getFoodData().getSaturationLevel());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", player.getX());
        pos.addProperty("y", player.getY());
        pos.addProperty("z", player.getZ());
        root.add("position", pos);

        boolean sameDimension = entity.level().dimension().equals(player.level().dimension());
        root.addProperty("same_dimension", sameDimension);
        root.addProperty("owner_dimension", player.level().dimension().identifier().toString());
        if (sameDimension) {
            root.addProperty("distance_to_me", entity.distanceTo(player));
        } else {
            root.addProperty("note", "owner is in a different dimension — their "
                    + "position is in THAT dimension's coordinates, not yours");
        }
        root.addProperty("main_hand", itemKey(player.getMainHandItem()));
        root.addProperty("off_hand", itemKey(player.getOffhandItem()));

        return root.toString();
    }

    private static String itemKey(ItemStack stack) {
        if (stack.isEmpty()) return "minecraft:air";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
