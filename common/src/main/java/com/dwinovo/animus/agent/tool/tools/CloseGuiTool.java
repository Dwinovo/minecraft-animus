package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.tool.AnimusTool;
import com.dwinovo.animus.entity.AnimusPlayer;
import com.google.gson.JsonObject;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code close_gui} — close the container GUI the companion has open (returns any cursor item to the
 * inventory, fires the close event). Call it when finished moving items. A synchronous server query.
 * (The menu also closes on its own if the companion walks out of range, so this is a courtesy/finish
 * action rather than strictly required.)
 */
public final class CloseGuiTool implements AnimusTool {

    @Override
    public String name() {
        return "close_gui";
    }

    @Override
    public String description() {
        return "Close the container GUI you currently have open — do this when you've finished moving "
                + "items. No arguments.";
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
        return 20;
    }

    @Override
    public boolean isQuery() {
        return true;
    }

    @Override
    public String executeQuery(JsonObject args, AnimusPlayer entity) {
        AbstractContainerMenu menu = entity.containerMenu;
        if (menu == null || menu == entity.inventoryMenu) {
            // The InventoryMenu (your own 2x2 grid + inventory) is always open — nothing to close.
            // If you left items in the 2x2 crafting grid, click_slot them back out.
            return "no block GUI was open (your own inventory menu is always available).";
        }
        entity.closeContainer();
        return "closed the GUI.";
    }
}
