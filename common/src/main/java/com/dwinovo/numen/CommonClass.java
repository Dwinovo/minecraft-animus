package com.dwinovo.numen;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.agent.tool.tools.BreakBlockTool;
import com.dwinovo.numen.agent.tool.tools.DropItemsTool;
import com.dwinovo.numen.agent.tool.tools.GetOwnerStatusTool;
import com.dwinovo.numen.agent.tool.tools.GetWorldInfoTool;
import com.dwinovo.numen.agent.tool.tools.LoadSkillTool;
import com.dwinovo.numen.agent.tool.tools.WaitTool;
import com.dwinovo.numen.agent.tool.tools.EquipTool;
import com.dwinovo.numen.agent.tool.tools.HuntTool;
import com.dwinovo.numen.agent.tool.tools.ShootTool;
import com.dwinovo.numen.agent.tool.tools.LocateStructureTool;
import com.dwinovo.numen.agent.tool.tools.CollectItemsTool;
import com.dwinovo.numen.agent.tool.tools.MineBlockTool;
import com.dwinovo.numen.agent.tool.tools.PlaceBlockTool;
import com.dwinovo.numen.agent.tool.tools.EatItemTool;
import com.dwinovo.numen.agent.tool.tools.ScanBlocksTool;
import com.dwinovo.numen.agent.tool.tools.ScanNearbyEntitiesTool;
import com.dwinovo.numen.agent.tool.tools.TodoWriteTool;
import com.dwinovo.numen.platform.Services;

/**
 * Loader-agnostic mod init. Called once from each platform's mod entry point
 * after the loader has finished registry-registration (entity types, payloads,
 * etc.). Everything that depends on the {@code Services} surface or that is
 * pure data-side initialisation lives here.
 */
public class CommonClass {

    public static void init() {
        Constants.LOG.info("[numen] common init on {} ({})",
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
     * tools ({@link com.dwinovo.numen.agent.tool.NumenTool#isLocal})
     * don't, they execute synchronously inside {@code EntityAgentLoop}.
     *
     * <p>The local tools (TodoWriteTool / LoadSkillTool) are registered on
     * both client and server, which is harmless: a dedicated server never
     * runs an LLM loop, so nothing ever calls {@code executeLocal} there.
     * Registering uniformly keeps the tool listing identical across both
     * sides for any future server-side validation (e.g. unknown-tool
     * rejection in {@code ExecuteToolPayload}).
     */
    public static void registerTools() {
        // Entity world-action + entity-perspective perception tools.
        // move_to — migrated to @NumenAction (world-action: returns a TaskRecord).
        ToolRegistry.register(com.dwinovo.numen.agent.tool.NumenTools.tool(
                new com.dwinovo.numen.agent.tool.tools.MovementTools(), "move_to"));
        ToolRegistry.register(new HuntTool());
        ToolRegistry.register(new ShootTool());
        ToolRegistry.register(new LocateStructureTool());
        ToolRegistry.register(new com.dwinovo.numen.agent.tool.tools.LocateBiomeTool());
        ToolRegistry.register(new CollectItemsTool());
        ToolRegistry.register(new MineBlockTool());
        ToolRegistry.register(new EquipTool());
        ToolRegistry.register(new PlaceBlockTool());
        ToolRegistry.register(new BreakBlockTool());
        ToolRegistry.register(new com.dwinovo.numen.agent.tool.tools.InteractAtTool());
        ToolRegistry.register(new com.dwinovo.numen.agent.tool.tools.InteractEntityTool());
        ToolRegistry.register(new EatItemTool());
        ToolRegistry.register(new WaitTool());
        ToolRegistry.register(new DropItemsTool());
        // GUI primitives — interact_at opens a menu, then the model inspects + clicks it directly.
        ToolRegistry.register(new com.dwinovo.numen.agent.tool.tools.InspectGuiTool());
        ToolRegistry.register(new com.dwinovo.numen.agent.tool.tools.TransferTool());
        ToolRegistry.register(new com.dwinovo.numen.agent.tool.tools.CloseGuiTool());
        // get_self_status — migrated to the @NumenAction authoring surface (dogfood).
        // Registered in place so the tool-list order (and prompt caching) is unchanged.
        ToolRegistry.register(com.dwinovo.numen.agent.tool.NumenTools.tool(
                new com.dwinovo.numen.agent.tool.tools.PerceptionTools(), "get_self_status"));
        ToolRegistry.register(new GetOwnerStatusTool());

        // Shared perception / planning tools.
        ToolRegistry.register(new com.dwinovo.numen.agent.tool.tools.LookupRecipeTool());
        ToolRegistry.register(new ScanNearbyEntitiesTool());
        ToolRegistry.register(new ScanBlocksTool());
        // inspect_block — migrated to @NumenAction (query with args).
        ToolRegistry.register(com.dwinovo.numen.agent.tool.NumenTools.tool(
                new com.dwinovo.numen.agent.tool.tools.PerceptionTools(), "inspect_block"));
        ToolRegistry.register(new com.dwinovo.numen.agent.tool.tools.InspectBlockStorageTool());
        ToolRegistry.register(new GetWorldInfoTool());
        ToolRegistry.register(new TodoWriteTool());
        ToolRegistry.register(new LoadSkillTool());

        Constants.LOG.info("[numen] registered {} tool(s)", ToolRegistry.size());
    }
}
