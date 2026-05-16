package com.dwinovo.animus.agent.skill;

import java.nio.file.Path;

/**
 * Immutable descriptor of a single loaded skill. Parsed from a {@code SKILL.md}
 * file on disk; produced by {@link SkillRegistry#scan}.
 *
 * @param name        slug from the {@code name:} frontmatter key. Used both
 *                    as the lookup key in the registry and as the argument
 *                    the LLM passes to the {@code load_skill} tool.
 * @param description short blurb from the {@code description:} frontmatter
 *                    key, surfaced in the {@code <available_skills>} XML
 *                    list. May be {@code null} — descriptionless skills are
 *                    deliberately hidden from the listing (matching opencode
 *                    behaviour), so the model never picks them blindly.
 * @param content     markdown body (everything after the closing {@code ---}).
 *                    Injected verbatim into the conversation when the model
 *                    loads the skill.
 * @param location    absolute path to the {@code SKILL.md} file on disk; used
 *                    for log lines and future "edit this skill" affordances.
 */
public record SkillInfo(String name, String description, String content, Path location) {}
