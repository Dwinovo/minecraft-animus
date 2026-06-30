package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;

import java.util.Map;

/** Client-local tool (raw NumenTool): loads a skill's instructions into the conversation. */
public final class LoadSkillTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final AgentTools impl = new AgentTools();

    private record Args(String name) {}

    @Override
    public String name() {
        return "load_skill";
    }

    @Override
    public String description() {
        return """
                Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.

                Use this tool to inject the skill's instructions and resources into the current conversation. The output contains detailed workflow guidance for the task.

                The skill name must match one of the skills listed in your system prompt's <available_skills> block.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("name", "The skill name from <available_skills> in the system prompt.")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            Args a = GSON.fromJson(call.rawArgs(), Args.class);
            call.complete(impl.loadSkill(a.name()));
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
