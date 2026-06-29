package com.dwinovo.numen;

import com.dwinovo.numen.agent.tool.NumenTools;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.agent.tool.tools.AgentTools;
import com.dwinovo.numen.agent.tool.tools.BlockActionTools;
import com.dwinovo.numen.agent.tool.tools.CombatTools;
import com.dwinovo.numen.agent.tool.tools.GuiTools;
import com.dwinovo.numen.agent.tool.tools.InventoryTools;
import com.dwinovo.numen.agent.tool.tools.LocateTools;
import com.dwinovo.numen.agent.tool.tools.MovementTools;
import com.dwinovo.numen.agent.tool.tools.PerceptionTools;
import com.dwinovo.numen.agent.tool.tools.QueryExtraTools;
import com.dwinovo.numen.agent.tool.tools.ScanTools;
import com.dwinovo.numen.agent.tool.tools.TodoWriteTool;
import com.dwinovo.numen.agent.tool.tools.TransferTool;
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
        // Tool implementations now live as @NumenAction methods grouped into
        // domain holders; the reflective adapter (NumenTools.tool) turns each into
        // a NumenTool. Registration ORDER is preserved exactly (prompt caching).
        MovementTools movement = new MovementTools();
        CombatTools combat = new CombatTools();
        LocateTools locate = new LocateTools();
        InventoryTools inventory = new InventoryTools();
        BlockActionTools blocks = new BlockActionTools();
        GuiTools gui = new GuiTools();
        PerceptionTools perception = new PerceptionTools();
        QueryExtraTools queries = new QueryExtraTools();
        ScanTools scan = new ScanTools();
        AgentTools agent = new AgentTools();

        // Entity world-action + entity-perspective perception tools.
        reg(movement, "move_to");
        reg(combat, "hunt");
        reg(combat, "shoot");
        reg(locate, "locate_structure");
        reg(locate, "locate_biome");
        reg(inventory, "collect_items");
        reg(blocks, "auto_mine");
        reg(inventory, "equip_item");
        reg(blocks, "place_block");
        reg(blocks, "break_block");
        reg(blocks, "interact_at");
        reg(blocks, "interact_entity");
        reg(inventory, "eat_item");
        reg(inventory, "wait");
        reg(inventory, "drop_items");
        // GUI primitives — interact_at opens a menu, then the model inspects + clicks it directly.
        reg(gui, "inspect_gui");
        ToolRegistry.register(new TransferTool());   // not yet on @NumenAction: object-array arg
        reg(gui, "close_gui");
        reg(perception, "get_self_status");
        reg(perception, "get_owner_status");

        // Shared perception / planning tools.
        reg(queries, "lookup_recipe");
        reg(queries, "scan_nearby_entities");
        reg(scan, "scan_blocks");
        reg(perception, "inspect_block");
        reg(queries, "inspect_block_storage");
        reg(perception, "get_world_info");
        ToolRegistry.register(new TodoWriteTool());  // not yet on @NumenAction: object-array arg
        reg(agent, "load_skill");

        Constants.LOG.info("[numen] registered {} tool(s)", ToolRegistry.size());
    }

    /** Register a single {@code @NumenAction} method from a holder, by tool name. */
    private static void reg(Object holder, String action) {
        ToolRegistry.register(NumenTools.tool(holder, action));
    }
}
