package com.dwinovo.animus.agent.skill;

import com.dwinovo.animus.Constants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Client-side registry of skills loaded from disk. Skills are markdown files
 * the LLM can load on demand via the {@code load_skill} tool, deferring long
 * "how to do X" instructions out of the system prompt and into the tool-call
 * conversation only when relevant.
 *
 * <h2>Why client-side only</h2>
 * Skills feed the LLM, and the LLM runs on the player's client (each player
 * pays their own tokens). Dedicated servers don't even instantiate this
 * registry — {@code load_skill} is an {@code isLocal} tool so its execute
 * path never crosses the network.
 *
 * <h2>Layout — one directory per skill</h2>
 * <pre>
 * &lt;gameDir&gt;/config/animus/skills/
 *   &lt;name&gt;/
 *     SKILL.md       (required — YAML frontmatter + markdown body)
 * </pre>
 * The directory name is informational; the canonical skill id is the
 * {@code name:} frontmatter key. Directory + frontmatter name don't have to
 * match (but conventionally do).
 *
 * <h2>Wire format injected into the system prompt</h2>
 * {@link #formatXml()} produces, every turn, a block of the form
 * <pre>
 * Skills provide specialized instructions and workflows for specific tasks.
 * Use the load_skill tool to load a skill when a task matches its description.
 * &lt;available_skills&gt;
 *   &lt;skill&gt;
 *     &lt;name&gt;build_hut&lt;/name&gt;
 *     &lt;description&gt;Build a small 2x2 hut at the player's location.&lt;/description&gt;
 *   &lt;/skill&gt;
 * &lt;/available_skills&gt;
 * </pre>
 * Verified against opencode's {@code Skill.fmt(verbose:true)} (anomalyco/opencode
 * {@code packages/opencode/src/skill/index.ts:262-281}); descriptionless
 * skills are intentionally hidden so the model doesn't pick them blindly.
 *
 * <h2>Thread safety</h2>
 * All access from the client main thread. The {@link #skills} map is
 * non-thread-safe; {@link #scan} runs synchronously on whatever thread the
 * caller chooses (currently the client init thread, which is the main
 * thread before the game loop starts).
 */
public final class SkillRegistry {

    /** Required filename inside each skill directory. Matches opencode's convention. */
    public static final String SKILL_FILENAME = "SKILL.md";

    private static final SkillRegistry INSTANCE = new SkillRegistry();

    /** Insertion-ordered for stable XML output across scans. */
    private final Map<String, SkillInfo> skills = new LinkedHashMap<>();

    /** Last scanned root, kept so a UI refresh button can re-scan without re-passing the path. */
    private Path lastScannedRoot;

    private SkillRegistry() {}

    public static SkillRegistry instance() {
        return INSTANCE;
    }

    /**
     * Walk one level deep under {@code skillsRoot}, parse every {@code SKILL.md}
     * found, replace the in-memory map atomically.
     *
     * <p>If {@code skillsRoot} is {@code null} or doesn't exist, the registry
     * is reset to empty — this is intentional so a misconfigured install
     * doesn't keep stale skills around.
     *
     * @return number of skills successfully loaded
     */
    public int scan(Path skillsRoot) {
        this.lastScannedRoot = skillsRoot;
        Map<String, SkillInfo> next = new LinkedHashMap<>();

        if (skillsRoot == null || !Files.isDirectory(skillsRoot)) {
            this.skills.clear();
            Constants.LOG.info("[animus-skill] root {} does not exist; 0 skills loaded",
                    skillsRoot);
            return 0;
        }

        int errors = 0;
        try (Stream<Path> dirs = Files.list(skillsRoot)) {
            for (Path dir : (Iterable<Path>) dirs.sorted()::iterator) {
                if (!Files.isDirectory(dir)) continue;
                Path skillFile = dir.resolve(SKILL_FILENAME);
                if (!Files.isRegularFile(skillFile)) continue;
                try {
                    SkillInfo info = loadFile(skillFile);
                    if (info == null) continue;
                    SkillInfo prev = next.put(info.name(), info);
                    if (prev != null) {
                        Constants.LOG.warn("[animus-skill] duplicate name '{}' at {} (replacing {})",
                                info.name(), info.location(), prev.location());
                    }
                } catch (IOException | RuntimeException ex) {
                    errors++;
                    Constants.LOG.warn("[animus-skill] failed to parse {}: {}",
                            skillFile, ex.toString());
                }
            }
        } catch (IOException ex) {
            Constants.LOG.warn("[animus-skill] failed to list {}: {}", skillsRoot, ex.toString());
        }

        this.skills.clear();
        this.skills.putAll(next);
        Constants.LOG.info("[animus-skill] scanned {}: {} skill(s) loaded, {} error(s)",
                skillsRoot, this.skills.size(), errors);
        return this.skills.size();
    }

    /** Re-scan the previously scanned root. No-op if {@link #scan} was never called. */
    public int rescan() {
        return scan(this.lastScannedRoot);
    }

    public Optional<SkillInfo> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public Collection<SkillInfo> all() {
        return skills.values();
    }

    public int size() {
        return skills.size();
    }

    /**
     * Build the XML block that gets appended to the system prompt every turn.
     * Returns the empty string when there are no described skills, so callers
     * can unconditionally append it.
     *
     * <p>Format reproduces opencode {@code Skill.fmt(verbose:true)} verbatim,
     * minus the {@code <location>} child (which opencode uses for IDE display
     * and the LLM doesn't need).
     */
    public String formatXml() {
        // Mirror opencode: only show skills with a description (the LLM can't
        // pick something it knows nothing about).
        var described = skills.values().stream()
                .filter(s -> s.description() != null && !s.description().isBlank())
                .toList();
        if (described.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(256);
        sb.append("Skills provide specialized instructions and workflows for specific tasks.\n");
        sb.append("Use the load_skill tool to load a skill when a task matches its description.\n");
        sb.append("<available_skills>\n");
        for (SkillInfo s : described) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(escapeXml(s.name())).append("</name>\n");
            sb.append("    <description>").append(escapeXml(s.description())).append("</description>\n");
            sb.append("  </skill>\n");
        }
        sb.append("</available_skills>");
        return sb.toString();
    }

    private static SkillInfo loadFile(Path skillFile) throws IOException {
        String raw = Files.readString(skillFile);
        SkillMarkdown.Parsed parsed = SkillMarkdown.parse(raw);
        String name = parsed.frontmatter().get("name");
        if (name == null || name.isBlank()) {
            Constants.LOG.warn("[animus-skill] {} missing required 'name:' frontmatter; skipping",
                    skillFile);
            return null;
        }
        String description = parsed.frontmatter().get("description");
        if (description != null && description.isBlank()) description = null;
        return new SkillInfo(name.trim(), description, parsed.content(), skillFile.toAbsolutePath());
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
