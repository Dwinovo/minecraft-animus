package com.dwinovo.animus.agent.skill;

import com.dwinovo.animus.Constants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * First-run extractor for built-in {@code SKILL.md} files. Copies the
 * mod-shipped skills out of the jar into {@code <gameDir>/config/animus/skills/}
 * exactly once per install, then never touches that directory again — the
 * sentinel file {@link #SENTINEL_FILENAME} marks "we've bootstrapped".
 *
 * <h2>Why a sentinel instead of per-file existence checks</h2>
 * The opposite design (Bukkit-style {@code saveResource(file, !exists)})
 * resurrects files the player intentionally deleted on next launch — that
 * breaks our contract: <em>after first run, the player owns the directory</em>.
 * The sentinel records "the batch has already been delivered"; per-file
 * existence checks survive only as a defensive guard against clobbering a
 * skill the player manually pre-created at the same name.
 *
 * <h2>Why a classpath manifest ({@code index.txt}) instead of jar walking</h2>
 * {@link ClassLoader} has no portable "list directory" API. The two
 * alternatives — {@link java.nio.file.FileSystems#newFileSystem} on a jar URI,
 * or {@code ResourceManager.findResources} — are either fragile across
 * shadowJar / fat-jar layouts or only available after Minecraft's resource
 * pipeline has started. A flat text manifest is one-line-per-skill and works
 * uniformly across Fabric, NeoForge, dev runs and shipped jars.
 *
 * <h2>Failure policy</h2>
 * Every per-skill failure (missing classpath resource, IO error, etc.) is
 * logged at WARN and the loop continues — partial bootstrap is better than
 * none. The sentinel is written even on partial success; the operator who
 * wants a retry deletes the sentinel manually.
 *
 * <p>If the manifest itself is missing, the sentinel is <strong>not</strong>
 * written — that's a packaging bug, not a normal state, so the next launch
 * gets another chance once the jar is fixed.
 *
 * <h2>Threading</h2>
 * Called from the client-init thread (inside the skill reload listener,
 * before {@link SkillRegistry#scan(Path)}). No concurrency concerns.
 *
 * @see SkillRegistry
 */
public final class BuiltinSkillBootstrap {

    /** Sentinel filename. Hidden by leading dot to keep player file browsers tidy. */
    public static final String SENTINEL_FILENAME = ".skills_bootstrapped";

    /** Classpath path to the manifest listing built-in skill names, one per line. */
    public static final String MANIFEST_RESOURCE = "assets/animus/skills/index.txt";

    /** Classpath prefix where each {@code <name>/SKILL.md} lives. */
    public static final String SKILL_RESOURCE_PREFIX = "assets/animus/skills/";

    /** Per-skill required filename, mirrored by {@link SkillRegistry#SKILL_FILENAME}. */
    public static final String SKILL_FILENAME = SkillRegistry.SKILL_FILENAME;

    private BuiltinSkillBootstrap() {}

    /**
     * Run the first-time extraction. Idempotent — subsequent calls see the
     * sentinel and exit immediately.
     *
     * @param animusConfigRoot {@code <gameDir>/config/animus/} — sentinel lives here
     * @param skillsDir        {@code <gameDir>/config/animus/skills/} — extraction target
     */
    public static void bootstrap(Path animusConfigRoot, Path skillsDir) {
        if (animusConfigRoot == null || skillsDir == null) {
            Constants.LOG.warn("[animus-skill-bootstrap] null path; skipping");
            return;
        }

        Path sentinel = animusConfigRoot.resolve(SENTINEL_FILENAME);
        if (Files.exists(sentinel)) {
            Constants.LOG.debug("[animus-skill-bootstrap] sentinel exists at {}; skip", sentinel);
            return;
        }

        ClassLoader cl = BuiltinSkillBootstrap.class.getClassLoader();
        List<String> names = readManifest(cl);
        if (names == null) {
            // Manifest missing → packaging bug. Don't write the sentinel; next
            // launch tries again once the jar is fixed.
            return;
        }

        try {
            Files.createDirectories(animusConfigRoot);
            Files.createDirectories(skillsDir);
        } catch (IOException ex) {
            Constants.LOG.warn("[animus-skill-bootstrap] failed to create {}: {}; aborting bootstrap",
                    skillsDir, ex.toString());
            return;
        }

        int copied = 0, skipped = 0, errors = 0;
        for (String name : names) {
            try {
                if (copySkill(cl, skillsDir, name)) {
                    copied++;
                } else {
                    skipped++;
                }
            } catch (IOException | RuntimeException ex) {
                errors++;
                Constants.LOG.warn("[animus-skill-bootstrap] failed to extract '{}': {}",
                        name, ex.toString());
            }
        }

        // Sentinel always carries timestamp + counts for post-mortem ("when did this happen?").
        String sentinelBody = "bootstrapped: " + Instant.now()
                + "\nbuilt-in count: " + copied
                + "\nskipped (already on disk): " + skipped
                + "\nerrors: " + errors + "\n";
        try {
            Files.writeString(sentinel, sentinelBody, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[animus-skill-bootstrap] failed to write sentinel {}: {}",
                    sentinel, ex.toString());
        }
        Constants.LOG.info("[animus-skill-bootstrap] extracted {} built-in skill(s) "
                + "({} skipped, {} errors); sentinel written at {}",
                copied, skipped, errors, sentinel);
    }

    /**
     * @return parsed manifest names (may be empty list), or {@code null}
     *         if the manifest resource itself is missing
     */
    private static List<String> readManifest(ClassLoader cl) {
        try (InputStream in = cl.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (in == null) {
                Constants.LOG.warn("[animus-skill-bootstrap] manifest {} not found on classpath; "
                        + "skipping bootstrap (sentinel NOT written, will retry next launch)",
                        MANIFEST_RESOURCE);
                return null;
            }
            List<String> out = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    out.add(trimmed);
                }
            }
            return out;
        } catch (IOException ex) {
            Constants.LOG.warn("[animus-skill-bootstrap] failed to read manifest: {}", ex.toString());
            return null;
        }
    }

    /**
     * @return {@code true} if the file was actually written, {@code false}
     *         if a target file already exists and was deliberately left alone
     */
    private static boolean copySkill(ClassLoader cl, Path skillsDir, String name) throws IOException {
        Path targetDir = skillsDir.resolve(name);
        Path targetFile = targetDir.resolve(SKILL_FILENAME);
        if (Files.exists(targetFile)) {
            Constants.LOG.debug("[animus-skill-bootstrap] '{}' already on disk at {}; leaving alone",
                    name, targetFile);
            return false;
        }

        String resourcePath = SKILL_RESOURCE_PREFIX + name + "/" + SKILL_FILENAME;
        try (InputStream src = cl.getResourceAsStream(resourcePath)) {
            if (src == null) {
                Constants.LOG.warn("[animus-skill-bootstrap] manifest lists '{}' but resource {} "
                        + "is missing; skipping", name, resourcePath);
                return false;
            }
            Files.createDirectories(targetDir);
            Files.copy(src, targetFile, StandardCopyOption.REPLACE_EXISTING);
            Constants.LOG.debug("[animus-skill-bootstrap] extracted {} → {}", resourcePath, targetFile);
            return true;
        }
    }
}
