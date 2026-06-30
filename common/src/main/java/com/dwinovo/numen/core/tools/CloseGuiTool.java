package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw NumenTool): close the open container GUI. */
public final class CloseGuiTool extends ServerNumenTool {

    private final GuiTools impl = new GuiTools();

    @Override
    public String name() {
        return "close_gui";
    }

    @Override
    public String description() {
        return "Close the container GUI you currently have open — do this when you've finished "
                + "moving items. No arguments.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        reply.accept(impl.closeGui(self));
    }
}
