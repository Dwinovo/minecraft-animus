package com.dwinovo.animus;

import com.dwinovo.animus.agent.tool.ToolRegistry;
import com.dwinovo.animus.agent.tool.tools.BreakBlockTool;
import com.dwinovo.animus.agent.tool.tools.DropItemsTool;
import com.dwinovo.animus.agent.tool.tools.GetOwnerStatusTool;
import com.dwinovo.animus.agent.tool.tools.GetSelfStatusTool;
import com.dwinovo.animus.agent.tool.tools.GetWorldInfoTool;
import com.dwinovo.animus.agent.tool.tools.InspectBlockTool;
import com.dwinovo.animus.agent.tool.tools.LoadSkillTool;
import com.dwinovo.animus.agent.tool.tools.WaitTool;
import com.dwinovo.animus.agent.tool.tools.EquipTool;
import com.dwinovo.animus.agent.tool.tools.HuntTool;
import com.dwinovo.animus.agent.tool.tools.ShootTool;
import com.dwinovo.animus.agent.tool.tools.LocateStructureTool;
import com.dwinovo.animus.agent.tool.tools.CollectItemsTool;
import com.dwinovo.animus.agent.tool.tools.MineBlockTool;
import com.dwinovo.animus.agent.tool.tools.PlaceBlockTool;
import com.dwinovo.animus.agent.tool.tools.EatItemTool;
import com.dwinovo.animus.agent.tool.tools.MoveToTool;
import com.dwinovo.animus.agent.tool.tools.ScanBlocksTool;
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
     * the companion task pipeline; local
     * tools ({@link com.dwinovo.animus.agent.tool.AnimusTool#isLocal})
     * don't, they execute synchronously inside {@code EntityAgentLoop}.
     *
     * <p>The local tools (TodoWriteTool / LoadSkillTool) are registered on
     * both client and server, which is harmless: a dedicated server never
     * runs an LLM loop, so nothing ever calls {@code executeLocal} there.
     * Registering uniformly keeps the tool listing identical across both
     * sides for any future server-side validation (e.g. unknown-tool
     * rejection in {@code ExecuteToolPayload}).
     */
    private static void registerTools() {
        // Entity world-action + entity-perspective perception tools.
        ToolRegistry.register(new MoveToTool());
        ToolRegistry.register(new HuntTool());
        ToolRegistry.register(new ShootTool());
        ToolRegistry.register(new LocateStructureTool());
        ToolRegistry.register(new com.dwinovo.animus.agent.tool.tools.LocateBiomeTool());
        ToolRegistry.register(new CollectItemsTool());
        ToolRegistry.register(new MineBlockTool());
        ToolRegistry.register(new EquipTool());
        ToolRegistry.register(new PlaceBlockTool());
        ToolRegistry.register(new BreakBlockTool());
        ToolRegistry.register(new com.dwinovo.animus.agent.tool.tools.InteractAtTool());
        ToolRegistry.register(new com.dwinovo.animus.agent.tool.tools.InteractEntityTool());
        ToolRegistry.register(new EatItemTool());
        ToolRegistry.register(new WaitTool());
        ToolRegistry.register(new DropItemsTool());
        // GUI primitives — interact_at opens a menu, then the model inspects + clicks it directly.
        ToolRegistry.register(new com.dwinovo.animus.agent.tool.tools.InspectGuiTool());
        ToolRegistry.register(new com.dwinovo.animus.agent.tool.tools.ClickSlotTool());
        ToolRegistry.register(new com.dwinovo.animus.agent.tool.tools.CraftTool());
        ToolRegistry.register(new com.dwinovo.animus.agent.tool.tools.CloseGuiTool());
        ToolRegistry.register(new GetSelfStatusTool());
        ToolRegistry.register(new GetOwnerStatusTool());

        // Shared perception / planning tools.
        ToolRegistry.register(new com.dwinovo.animus.agent.tool.tools.LookupRecipeTool());
        ToolRegistry.register(new ScanNearbyEntitiesTool());
        ToolRegistry.register(new ScanBlocksTool());
        ToolRegistry.register(new InspectBlockTool());
        ToolRegistry.register(new GetWorldInfoTool());
        ToolRegistry.register(new TodoWriteTool());
        ToolRegistry.register(new LoadSkillTool());

        Constants.LOG.info("[animus] registered {} tool(s)", ToolRegistry.size());
    }
}
