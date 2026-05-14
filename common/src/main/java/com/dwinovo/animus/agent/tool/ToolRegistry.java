package com.dwinovo.animus.agent.tool;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mod-global registry of {@link AnimusTool} instances. Populated once during
 * mod init (see {@code CommonClass.init} / per-loader entry points) and
 * read-only thereafter. Insertion order is preserved so the LLM sees tools
 * in a stable order across requests — useful for prompt caching on backends
 * that hash tool definitions.
 *
 * <h2>Why static singleton instead of per-entity</h2>
 * The tool set is a deployment-level decision (which capabilities does the
 * mod expose?), not a per-entity decision. Putting it on the entity would
 * mean every Animus carries an identical copy, wasting memory and creating
 * a sync question (does each entity get its own ToolRegistry? on world load
 * do they get repopulated?). Global is simpler and matches how vanilla
 * registries work.
 */
public final class ToolRegistry {

    private static final Map<String, AnimusTool> TOOLS = new LinkedHashMap<>();

    private ToolRegistry() {}

    /**
     * Register a tool. Should only be called during mod init. Throws on
     * duplicate name — silent replacement would mask wiring bugs.
     */
    public static void register(AnimusTool tool) {
        AnimusTool prior = TOOLS.put(tool.name(), tool);
        if (prior != null) {
            throw new IllegalStateException(
                    "Duplicate AnimusTool name: " + tool.name()
                            + " (was " + prior.getClass().getName()
                            + ", now " + tool.getClass().getName() + ")");
        }
    }

    public static AnimusTool get(String name) {
        return TOOLS.get(name);
    }

    public static Collection<AnimusTool> all() {
        return Collections.unmodifiableCollection(TOOLS.values());
    }

    public static int size() {
        return TOOLS.size();
    }
}
