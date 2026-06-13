package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code get_self_status} tool — the ONE self-awareness call: HP,
 * position, dimension, equipment (hands + armor), the full backpack
 * inventory, current attack target, and movement flags.
 *
 * <h2>Why one tool instead of status + storage</h2>
 * They were separate (get_self_status / get_storage) and the model kept
 * making decisions on half a picture — most famously concluding an equipped
 * bucket had vanished because the storage call only listed the backpack.
 * One complete snapshot per call costs a few hundred tokens and removes an
 * entire class of partial-view mistakes.
 *
 * <h2>Why this is a server query</h2>
 * A working companion may be far beyond the owner's client tracking range
 * (chunk-ticket-loaded terrain the client never sees). Server-side execution
 * reads the authoritative entity wherever it is; the round-trip is one tick.
 */
public final class GetSelfStatusTool implements AnimusTool {

    @Override
    public String name() {
        return "get_self_status";
    }

    @Override
    public String description() {
        return "Read your complete status in one call: HP / max HP, position, "
                + "dimension, equipment (hands + armor — an equipped item leaves "
                + "the backpack, it is NOT lost), your full backpack inventory, "
                + "current attack target, and movement state. ALWAYS call this "
                + "before combat or planning decisions and periodically during "
                + "long tasks. No arguments.";
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
        return 1;  // query, ignored
    }

    @Override
    public boolean isQuery() {
        return true;
    }

    @Override
    public String executeQuery(JsonObject args, AnimusPlayer entity) {
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

        // Equipment: hands + armor. Lives OUTSIDE the backpack container.
        JsonObject equipment = new JsonObject();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = entity.getItemBySlot(slot);
            if (s.isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            if (s.getCount() > 1) o.addProperty("count", s.getCount());
            equipment.add(slot.getName(), o);
        }
        root.add("equipment", equipment);

        // Full backpack inventory (empty slots omitted).
        var inv = entity.getInventory();
        JsonArray items = new JsonArray();
        int used = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            used++;
            JsonObject o = new JsonObject();
            o.addProperty("slot", i);
            o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            o.addProperty("count", s.getCount());
            items.add(o);
        }
        JsonObject inventory = new JsonObject();
        inventory.add("items", items);
        inventory.addProperty("slots_used", used);
        inventory.addProperty("slots_total", inv.getContainerSize());
        root.add("inventory", inventory);

        // A player body has no AI attack-target; combat is task-driven.
        root.add("target", JsonNull.INSTANCE);

        root.addProperty("on_ground", entity.onGround());
        root.addProperty("in_water", entity.isInWater());
        root.addProperty("in_lava", entity.isInLava());

        return root.toString();
    }
}
