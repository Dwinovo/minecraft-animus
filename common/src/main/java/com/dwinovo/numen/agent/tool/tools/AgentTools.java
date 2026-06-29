package com.dwinovo.numen.agent.tool.tools;

import com.dwinovo.numen.agent.skill.SkillInfo;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.agent.tool.api.Arg;
import com.dwinovo.numen.agent.tool.api.NumenAction;

import java.util.stream.Collectors;

/**
 * Agent-side (client-local) tools authored on the {@link NumenAction} surface.
 * These run on the agent thread with no server body — the adapter infers LOCAL
 * because the method takes neither the live entity nor a reply callback and
 * returns its result directly.
 */
public final class AgentTools {

    @NumenAction(name = "load_skill", description = """
            Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.

            Use this tool to inject the skill's instructions and resources into the current conversation. The output contains detailed workflow guidance for the task.

            The skill name must match one of the skills listed in your system prompt's <available_skills> block.""")
    public String loadSkill(@Arg("The skill name from <available_skills> in the system prompt.") String name) {
        SkillRegistry registry = SkillRegistry.instance();
        var maybe = registry.get(name);
        if (maybe.isEmpty()) {
            String available = registry.all().stream()
                    .map(SkillInfo::name)
                    .map(AgentTools::quote)
                    .collect(Collectors.joining(","));
            return "{\"success\":false,\"error\":\"unknown skill: " + escapeJson(name)
                    + "\",\"available\":[" + available + "]}";
        }

        SkillInfo info = maybe.get();
        // Match opencode's <skill_content name="X"># Skill: X\n{content}\n</skill_content>
        StringBuilder out = new StringBuilder(info.content().length() + 128);
        out.append("<skill_content name=\"").append(escapeXmlAttr(info.name())).append("\">\n");
        out.append("# Skill: ").append(info.name()).append("\n\n");
        out.append(info.content().trim());
        out.append("\n</skill_content>");
        return out.toString();
    }

    private static String quote(String s) {
        return "\"" + escapeJson(s) + "\"";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeXmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
