package com.dwinovo.animus.agent.tool.tools;

import com.dwinovo.animus.agent.skill.SkillInfo;
import com.dwinovo.animus.agent.skill.SkillRegistry;
import com.dwinovo.animus.agent.tool.AnimusTool;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * On-demand skill loader. The LLM scans the {@code <available_skills>} XML
 * listing in its system prompt (produced by {@link SkillRegistry#formatXml()})
 * and, when a task matches a description, calls this tool to inject the
 * skill's full markdown body into the conversation.
 *
 * <h2>Lazy loading rationale</h2>
 * Skills can be long (workflows, step-by-step recipes, references to other
 * files). Putting every one of them into the system prompt up-front bloats
 * tokens and dilutes attention. The XML listing is the table of contents;
 * this tool fetches a single chapter.
 *
 * <h2>Output shape</h2>
 * Mirrors opencode's {@code tool/skill.ts} (anomalyco/opencode) wrapping the
 * markdown in a {@code <skill_content name="...">} block so the model can
 * cleanly delimit where the skill text ends and its own subsequent reasoning
 * begins. The {@code <skill_files>} child opencode emits is intentionally
 * omitted — we don't have a "skill directory with sibling scripts" concept,
 * one {@code SKILL.md} is the whole skill.
 *
 * <h2>Self-correction on miss</h2>
 * When the skill name doesn't match, returns a structured JSON error with the
 * list of currently available names. This is the same "actionable error"
 * pattern Anthropic recommends: don't let the LLM guess, hand it the right
 * choices and let it retry on the next turn.
 */
public final class LoadSkillTool implements AnimusTool {

    public static final String TOOL_NAME = "load_skill";

    @Override
    public String name() {
        return TOOL_NAME;
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
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "The skill name from <available_skills> in the system prompt."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public long defaultTimeoutTicks() {
        return 1L; // unused — local tool
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public String executeLocal(JsonObject args) {
        if (args == null || !args.has("name") || args.get("name").isJsonNull()) {
            throw new IllegalArgumentException("missing required argument: name");
        }
        String name;
        try {
            name = args.get("name").getAsString();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("'name' must be a string: " + ex.getMessage());
        }

        SkillRegistry registry = SkillRegistry.instance();
        var maybe = registry.get(name);
        if (maybe.isEmpty()) {
            String available = registry.all().stream()
                    .map(SkillInfo::name)
                    .map(LoadSkillTool::quote)
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
