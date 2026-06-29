package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.tool.api.NumenAction;
import com.dwinovo.numen.agent.tool.api.ToolSchema;

import java.lang.reflect.Method;

/**
 * Entry point for registering {@link NumenAction}-annotated tools — the same
 * path Numen's own tools and third-party addon mods use.
 *
 * <p>{@link #register(Object)} registers every {@code @NumenAction} method on a
 * holder object. {@link #tool(Object, String)} builds a single tool by name,
 * for callers (like the built-in registration) that need to control the exact
 * position of each tool in the registry.
 */
public final class NumenTools {

    private NumenTools() {}

    /** Register every {@code @NumenAction} method declared on {@code holder}. */
    public static void register(Object holder) {
        for (Method m : holder.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(NumenAction.class)) {
                ToolRegistry.register(new NumenActionTool(holder, m));
            }
        }
    }

    /** Build (do not register) the adapter tool for a single named action on {@code holder}. */
    public static NumenTool tool(Object holder, String actionName) {
        for (Method m : holder.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(NumenAction.class)
                    && ToolSchema.actionName(m).equals(actionName)) {
                return new NumenActionTool(holder, m);
            }
        }
        throw new IllegalArgumentException(
                "no @NumenAction named '" + actionName + "' on " + holder.getClass().getName());
    }
}
