package com.dwinovo.animus.agent.skill;

import java.util.HashMap;
import java.util.Map;

/**
 * Tiny YAML-frontmatter parser for {@code SKILL.md} files. Intentionally
 * minimal — supports only single-line {@code key: value} pairs in the
 * frontmatter block, no nesting, no arrays, no multi-line strings.
 *
 * <h2>Why no SnakeYAML / Jackson</h2>
 * Project policy is "zero third-party runtime deps" (jar ~260KB). The skill
 * frontmatter only needs {@code name} + {@code description}, both plain
 * strings — a 30-line custom parser is strictly cheaper than dragging in a
 * 1MB YAML library for one feature.
 *
 * <h2>Grammar</h2>
 * <pre>
 * SKILL.md   ::= FRONTMATTER? CONTENT
 * FRONTMATTER ::= "---" NEWLINE (KEY ":" VALUE NEWLINE)* "---" NEWLINE
 * KEY        ::= [A-Za-z0-9_-]+
 * VALUE      ::= any-chars-until-newline   (leading/trailing whitespace trimmed,
 *                optional surrounding single or double quotes stripped)
 * CONTENT    ::= rest of file
 * </pre>
 *
 * <h2>Tolerance</h2>
 * <ul>
 *   <li>Missing frontmatter → {@link Parsed#frontmatter} is empty,
 *       {@link Parsed#content} is the whole file.</li>
 *   <li>Frontmatter opens but never closes → treated as no frontmatter.</li>
 *   <li>Lines inside frontmatter without a {@code ":"} → skipped silently.</li>
 *   <li>Duplicate keys → last one wins (matches YAML behaviour).</li>
 * </ul>
 */
public final class SkillMarkdown {

    private static final String DELIMITER = "---";

    private SkillMarkdown() {}

    public record Parsed(Map<String, String> frontmatter, String content) {}

    public static Parsed parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Parsed(Map.of(), "");
        }
        // Normalize line endings so we can split on '\n' alone.
        String[] lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);

        if (lines.length == 0 || !DELIMITER.equals(lines[0].trim())) {
            // No frontmatter — whole file is content.
            return new Parsed(Map.of(), raw);
        }

        Map<String, String> fm = new HashMap<>();
        int i = 1;
        boolean closed = false;
        while (i < lines.length) {
            String line = lines[i];
            if (DELIMITER.equals(line.trim())) {
                closed = true;
                i++;
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = stripQuotes(line.substring(colon + 1).trim());
                if (!key.isEmpty()) {
                    fm.put(key, value);
                }
            }
            i++;
        }

        if (!closed) {
            // Opened "---" but never closed — treat whole file as content,
            // ignore the half-eaten frontmatter.
            return new Parsed(Map.of(), raw);
        }

        // Rebuild remaining content from line i onward, preserving original
        // line breaks (we used split with -1 so trailing empty lines survive).
        StringBuilder body = new StringBuilder();
        for (int j = i; j < lines.length; j++) {
            if (j > i) body.append('\n');
            body.append(lines[j]);
        }
        return new Parsed(fm, body.toString());
    }

    private static String stripQuotes(String v) {
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }
}
