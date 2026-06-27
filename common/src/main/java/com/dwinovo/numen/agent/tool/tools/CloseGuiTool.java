package com.dwinovo.numen.agent.tool.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
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
public final class CloseGuiTool implements NumenTool {

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
    public String executeQuery(JsonObject args, NumenPlayer entity) {
        AbstractContainerMenu menu = entity.containerMenu;
        if (menu == null || menu == entity.inventoryMenu) {
            // The InventoryMenu (your own 2x2 grid + inventory) is always open — nothing to close.
            // If you left items in the 2x2 crafting grid, transfer them back out.
            return TaskResult.ok("no block GUI was open (your own inventory menu is always available).").toJson();
        }
        entity.closeContainer();
        return TaskResult.ok("closed the GUI.").toJson();
    }
}
