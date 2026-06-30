package com.dwinovo.numen.core;

import com.dwinovo.numen.core.tool.NumenTools;
import com.dwinovo.numen.core.tool.CoreServerTools;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.CompanionLifecycle;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.core.net.CancelTasksPayload;
import com.dwinovo.numen.core.net.ExecuteToolPayload;
import com.dwinovo.numen.core.net.TaskResultPayload;
import com.dwinovo.numen.core.task.CompanionTaskFactory;
import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.BreakBlockCompanionTask;
import com.dwinovo.numen.core.task.BreakBlockTaskRecord;
import com.dwinovo.numen.core.task.CollectItemsTaskGoal;
import com.dwinovo.numen.core.task.CollectItemsTaskRecord;
import com.dwinovo.numen.core.task.DropCompanionTask;
import com.dwinovo.numen.core.task.DropItemsTaskRecord;
import com.dwinovo.numen.core.task.EatCompanionTask;
import com.dwinovo.numen.core.task.EatItemTaskRecord;
import com.dwinovo.numen.core.task.EquipCompanionTask;
import com.dwinovo.numen.core.task.EquipTaskRecord;
import com.dwinovo.numen.core.task.HuntCompanionTask;
import com.dwinovo.numen.core.task.HuntTaskRecord;
import com.dwinovo.numen.core.task.InteractAtCompanionTask;
import com.dwinovo.numen.core.task.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.InteractEntityCompanionTask;
import com.dwinovo.numen.core.task.InteractEntityTaskRecord;
import com.dwinovo.numen.core.task.LocateBiomeTaskGoal;
import com.dwinovo.numen.core.task.LocateBiomeTaskRecord;
import com.dwinovo.numen.core.task.LocateStructureTaskGoal;
import com.dwinovo.numen.core.task.LocateStructureTaskRecord;
import com.dwinovo.numen.core.task.MineBlockTaskRecord;
import com.dwinovo.numen.core.task.MineCompanionTask;
import com.dwinovo.numen.core.task.MoveToCompanionTask;
import com.dwinovo.numen.core.task.MoveToTaskRecord;
import com.dwinovo.numen.core.task.PlaceBlockCompanionTask;
import com.dwinovo.numen.core.task.PlaceBlockTaskRecord;
import com.dwinovo.numen.core.task.ShootCompanionTask;
import com.dwinovo.numen.core.task.ShootTaskRecord;
import com.dwinovo.numen.core.task.WaitCompanionTask;
import com.dwinovo.numen.core.task.WaitTaskRecord;
import com.dwinovo.numen.core.tools.AgentTools;
import com.dwinovo.numen.core.tools.BlockActionTools;
import com.dwinovo.numen.core.tools.CombatTools;
import com.dwinovo.numen.core.tools.ContainerTools;
import com.dwinovo.numen.core.tools.GuiTools;
import com.dwinovo.numen.core.tools.InventoryTools;
import com.dwinovo.numen.core.tools.LocateTools;
import com.dwinovo.numen.core.tools.MovementTools;
import com.dwinovo.numen.core.tools.PerceptionTools;
import com.dwinovo.numen.core.tools.QueryExtraTools;
import com.dwinovo.numen.core.tools.ScanTools;

/**
 * Loader-agnostic init for the {@code numen-core} tool pack — the worked example
 * of how a mod adds tools to the {@code numen-api} engine. Each loader entry
 * point calls {@link #init()} once (on both sides: a dedicated server runs the
 * task bodies), then registers its own server-tick hooks for the tools that need
 * per-tick server work (scans, the pathfinder caches).
 *
 * <p>Two things plug into the engine here:
 * <ul>
 *   <li>tools — {@code @NumenAction} holders adapted by {@link NumenTools} and
 *       added to the global {@link ToolRegistry} (order preserved for prompt
 *       caching);</li>
 *   <li>task runners — each {@code TaskRecord} type a world-action tool emits is
 *       paired with the {@code CompanionTask} that runs it, via
 *       {@link CompanionTaskFactory#register}.</li>
 * </ul>
 */
public final class NumenCore {

    private static boolean initialised = false;

    private NumenCore() {}

    public static void init() {
        if (initialised) return;
        initialised = true;
        registerTools();
        registerTaskRunners();
        registerTransport();
        Constants.LOG.info("[numen-core] registered {} tool(s), {} task type(s)",
                ToolRegistry.size(), CompanionTaskFactory.size());
    }

    /**
     * Core's own server-side execution wiring — none of it is the engine's: our
     * three transport packets (client ships a body-bound tool, server replies),
     * and the engine's {@link CompanionLifecycle} seam used to finalize our
     * per-companion tasks on death / removal / owner-abort.
     */
    private static void registerTransport() {
        Services.NETWORK.registerClientToServer(
                ExecuteToolPayload.TYPE, ExecuteToolPayload.STREAM_CODEC, ExecuteToolPayload::handle);
        Services.NETWORK.registerServerToClient(
                TaskResultPayload.TYPE, TaskResultPayload.STREAM_CODEC, TaskResultPayload::handle);
        Services.NETWORK.registerClientToServer(
                CancelTasksPayload.TYPE, CancelTasksPayload.STREAM_CODEC, CancelTasksPayload::handle);

        CompanionLifecycle.onDeath(CompanionTickDispatcher::clearActiveTask);
        CompanionLifecycle.onRemove(CompanionTickDispatcher::onCompanionRemoved);
        CompanionLifecycle.onAbort(CoreServerTools::abort);
    }

    private static void registerTools() {
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
        ContainerTools container = new ContainerTools();

        // Registration ORDER is preserved (backends with prompt-caching keyed off
        // the tool list cache stably across requests).
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
        ToolRegistry.register(new com.dwinovo.numen.core.tools.WaitTool());   // SAMPLE: raw NumenTool, no @NumenAction
        reg(inventory, "drop_items");
        reg(gui, "inspect_gui");
        reg(container, "transfer");
        reg(gui, "close_gui");
        ToolRegistry.register(new com.dwinovo.numen.core.tools.GetSelfStatusTool());   // SAMPLE: raw NumenTool
        ToolRegistry.register(new com.dwinovo.numen.core.tools.GetOwnerStatusTool());
        reg(queries, "lookup_recipe");
        reg(queries, "scan_nearby_entities");
        reg(scan, "scan_blocks");
        ToolRegistry.register(new com.dwinovo.numen.core.tools.InspectBlockTool());
        reg(queries, "inspect_block_storage");
        ToolRegistry.register(new com.dwinovo.numen.core.tools.GetWorldInfoTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.TodoWriteTool());   // raw NumenTool
        ToolRegistry.register(new com.dwinovo.numen.core.tools.LoadSkillTool());   // raw NumenTool
    }

    private static void reg(Object holder, String action) {
        ToolRegistry.register(NumenTools.tool(holder, action));
    }

    private static void registerTaskRunners() {
        CompanionTaskFactory.register(MoveToTaskRecord.class, (p, r) -> new MoveToCompanionTask(p, r));
        CompanionTaskFactory.register(MineBlockTaskRecord.class, (p, r) -> new MineCompanionTask(p, r));
        CompanionTaskFactory.register(EquipTaskRecord.class, (p, r) -> new EquipCompanionTask(p, r));
        CompanionTaskFactory.register(WaitTaskRecord.class, (p, r) -> new WaitCompanionTask(p, r));
        CompanionTaskFactory.register(DropItemsTaskRecord.class, (p, r) -> new DropCompanionTask(p, r));
        CompanionTaskFactory.register(BreakBlockTaskRecord.class, (p, r) -> new BreakBlockCompanionTask(p, r));
        CompanionTaskFactory.register(EatItemTaskRecord.class, (p, r) -> new EatCompanionTask(p, r));
        CompanionTaskFactory.register(HuntTaskRecord.class, (p, r) -> new HuntCompanionTask(p, r));
        CompanionTaskFactory.register(ShootTaskRecord.class, (p, r) -> new ShootCompanionTask(p, r));
        CompanionTaskFactory.register(CollectItemsTaskRecord.class, (p, r) -> new CollectItemsTaskGoal(p, r));
        CompanionTaskFactory.register(PlaceBlockTaskRecord.class, (p, r) -> new PlaceBlockCompanionTask(p, r));
        CompanionTaskFactory.register(InteractAtTaskRecord.class, (p, r) -> new InteractAtCompanionTask(p, r));
        CompanionTaskFactory.register(InteractEntityTaskRecord.class, (p, r) -> new InteractEntityCompanionTask(p, r));
        CompanionTaskFactory.register(LocateStructureTaskRecord.class, (p, r) -> new LocateStructureTaskGoal(p, r));
        CompanionTaskFactory.register(LocateBiomeTaskRecord.class, (p, r) -> new LocateBiomeTaskGoal(p, r));
    }
}
