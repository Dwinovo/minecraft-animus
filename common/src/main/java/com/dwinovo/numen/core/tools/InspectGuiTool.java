package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw NumenTool): look at the currently open GUI / inventory menu. */
public final class InspectGuiTool extends ServerNumenTool {

    private final GuiTools impl = new GuiTools();

    @Override
    public String name() {
        return "inspect_gui";
    }

    @Override
    public String description() {
        return "Look at the GUI you currently have open. After interact_at right-clicks a chest / "
                + "furnace / machine it shows that container; with NO container open it shows YOUR own "
                + "inventory menu (which includes a 2x2 crafting grid), so you can craft small recipes "
                + "without a table. Lists every slot — index, side, item + count, [output] mark — plus "
                + "the cursor and any machine progress. If a crafting grid is open it draws the grid as "
                + "a 2D map of slot numbers (handy for hand-loading a modded grid). Use it to choose "
                + "transfer slot indices and to verify a transfer. No arguments.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        reply.accept(impl.inspectGui(self));
    }
}
