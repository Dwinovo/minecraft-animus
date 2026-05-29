package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AgentRole;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.agent.tool.ClientToolContext;
import com.dwinovo.animus.client.data.ClientAnimusInventories;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read the shared virtual chest contents. Used by PlayerAgent to plan
 * resource-related work ("do we need more iron?"). EntityAgents can also
 * call it to verify materials before starting subtasks.
 */
public final class GetStorageTool implements AnimusTool {

    @Override
    public String name() { return "get_storage"; }

    @Override
    public String description() {
        return "Read the contents of your own inventory. Returns a list of "
                + "{slot, item_id, count, max_stack_size} for each non-empty "
                + "slot. Empty slots are omitted. Use this to check what you "
                + "are carrying before / after gathering.";
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
    public Set<AgentRole> allowedRoles() {
        return EnumSet.of(AgentRole.PLAYER, AgentRole.ENTITY);
    }

    @Override
    public String executeLocal(JsonObject args, ClientToolContext ctx) {
        ItemStack[] slots = ClientAnimusInventories.get(ctx.vanillaEntityId());
        JsonArray items = new JsonArray();
        int used = 0;
        for (int i = 0; i < slots.length; i++) {
            ItemStack s = slots[i];
            if (s.isEmpty()) continue;
            used++;
            JsonObject o = new JsonObject();
            o.addProperty("slot", i);
            o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            o.addProperty("count", s.getCount());
            o.addProperty("max_stack_size", s.getMaxStackSize());
            items.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("items", items);
        root.addProperty("slots_used", used);
        root.addProperty("slots_total", slots.length);
        return root.toString();
    }
}
