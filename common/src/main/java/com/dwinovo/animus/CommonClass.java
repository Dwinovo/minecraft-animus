package com.dwinovo.animus;

import com.dwinovo.animus.agent.tool.ToolRegistry;
import com.dwinovo.animus.agent.tool.tools.AssignTaskTool;
import com.dwinovo.animus.agent.tool.tools.AttackTargetTool;
import com.dwinovo.animus.agent.tool.tools.GetMyStatusTool;
import com.dwinovo.animus.agent.tool.tools.GetOwnerStatusTool;
import com.dwinovo.animus.agent.tool.tools.GetSelfStatusTool;
import com.dwinovo.animus.agent.tool.tools.GetStorageTool;
import com.dwinovo.animus.agent.tool.tools.GetWorldInfoTool;
import com.dwinovo.animus.agent.tool.tools.InspectBlockTool;
import com.dwinovo.animus.agent.tool.tools.LoadSkillTool;
import com.dwinovo.animus.agent.tool.tools.MineBlockTool;
import com.dwinovo.animus.agent.tool.tools.MoveToTool;
import com.dwinovo.animus.agent.tool.tools.PathfindAndMineTool;
import com.dwinovo.animus.agent.tool.tools.RecallUnitTool;
import com.dwinovo.animus.agent.tool.tools.ScanNearbyEntitiesTool;
import com.dwinovo.animus.agent.tool.tools.TodoWriteTool;
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
     * <p>Adding a new tool: instantiate it here. World-action tools also need
     * a matching {@code Goal} added in
     * {@link com.dwinovo.animus.entity.AnimusEntity#registerGoals}; local
     * tools ({@link com.dwinovo.animus.agent.tool.AnimusTool#isLocal})
     * don't, they execute synchronously inside {@code ClientAgentLoop}.
     *
     * <p>The local tools (TodoWriteTool / LoadSkillTool) are registered on
     * both client and server, which is harmless: a dedicated server never
     * runs an LLM loop, so nothing ever calls {@code executeLocal} there.
     * Registering uniformly keeps the tool listing identical across both
     * sides for any future server-side validation (e.g. unknown-tool
     * rejection in {@code ExecuteToolPayload}).
     */
    private static void registerTools() {
        // EntityAgent tools — world-action + entity-perspective perception.
        ToolRegistry.register(new MoveToTool());
        ToolRegistry.register(new AttackTargetTool());
        ToolRegistry.register(new MineBlockTool());
        ToolRegistry.register(new PathfindAndMineTool());
        ToolRegistry.register(new GetSelfStatusTool());
        ToolRegistry.register(new GetOwnerStatusTool());

        // PlayerAgent tools — dispatch + player-perspective perception.
        ToolRegistry.register(new AssignTaskTool());
        ToolRegistry.register(new RecallUnitTool());
        ToolRegistry.register(new GetMyStatusTool());
        ToolRegistry.register(new GetStorageTool());

        // Shared tools — work for either role via ctx.anchor().
        ToolRegistry.register(new ScanNearbyEntitiesTool());
        ToolRegistry.register(new InspectBlockTool());
        ToolRegistry.register(new GetWorldInfoTool());
        ToolRegistry.register(new TodoWriteTool());
        ToolRegistry.register(new LoadSkillTool());

        Constants.LOG.info("[animus] registered {} tool(s)", ToolRegistry.size());
    }
}
