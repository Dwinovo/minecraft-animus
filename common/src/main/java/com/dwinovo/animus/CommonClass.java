package com.dwinovo.animus;

import com.dwinovo.animus.agent.tool.ToolRegistry;
import com.dwinovo.animus.agent.tool.tools.MoveToTool;
import com.dwinovo.animus.platform.Services;

/**
 * Loader-agnostic mod init. Called once from each platform's mod entry point
 * after the loader has finished registry-registration (entity types, payloads,
 * etc.). Everything that depends on the {@code Services} surface or that is
 * pure data-side initialisation lives here.
 */
public class CommonClass {

    public static void init() {
        Constants.LOG.info("[animus] common init on {} ({})",
                Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());

        registerTools();
    }

    /**
     * Populate the global {@link ToolRegistry}. Order is preserved in the
     * registry, so backends with prompt-caching keyed off the tool list will
     * cache stably across requests.
     *
     * <p>Adding a new tool: instantiate it here. The Goal that consumes its
     * task records must be added in {@link com.dwinovo.animus.entity.AnimusEntity#registerGoals}.
     */
    private static void registerTools() {
        ToolRegistry.register(new MoveToTool());
        Constants.LOG.info("[animus] registered {} tool(s)", ToolRegistry.size());
    }
}
