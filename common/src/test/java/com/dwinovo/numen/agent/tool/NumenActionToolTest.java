package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.tool.api.Arg;
import com.dwinovo.numen.agent.tool.api.NumenAction;
import com.dwinovo.numen.agent.tool.tools.*;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.tasks.MoveToTaskRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zero-regression proof for the {@code @NumenAction} migrations: each reflected
 * tool must present the <em>identical</em> LLM-facing surface (name, description,
 * parameter schema) AND infer the <em>same</em> execution category as the
 * hand-written class it replaces, so tool selection and routing can't shift. The
 * originals are kept as oracles until they are deleted as a batch. Adding a
 * migrated tool here is one line.
 */
class NumenActionToolTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // A few tool classes touch registries at class-init; bring the registries up.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static void check(List<String> out, NumenTool migrated, NumenTool original) {
        String id = original.name();
        if (!original.name().equals(migrated.name())) out.add("name: " + id);
        if (!original.description().equals(migrated.description())) out.add("description: " + id);
        if (!original.parameterSchema().equals(migrated.parameterSchema())) {
            out.add(schemaDiff(id, original.parameterSchema(), migrated.parameterSchema()));
        }
        if (original.isQuery() != migrated.isQuery()) out.add("isQuery: " + id);
        if (original.isLocal() != migrated.isLocal()) out.add("isLocal: " + id);
        if (original.isAsyncQuery() != migrated.isAsyncQuery()) out.add("isAsyncQuery: " + id);
    }

    /** Compact per-property diff of two schemas, so all drift shows in one run. */
    @SuppressWarnings("unchecked")
    private static String schemaDiff(String id, Map<String, Object> exp, Map<String, Object> act) {
        StringBuilder sb = new StringBuilder("schema: " + id);
        if (!Objects.equals(exp.get("required"), act.get("required"))) {
            sb.append("\n    required exp=").append(exp.get("required")).append(" act=").append(act.get("required"));
        }
        Map<String, Object> ep = (Map<String, Object>) exp.get("properties");
        Map<String, Object> ap = (Map<String, Object>) act.get("properties");
        for (String k : ep.keySet()) {
            if (!Objects.equals(ep.get(k), ap.get(k))) {
                sb.append("\n    .").append(k).append(" exp=").append(ep.get(k)).append(" act=").append(ap.get(k));
            }
        }
        return sb.toString();
    }

    @Test
    void migratedToolsMatchOriginalSurface() {
        List<String> p = new ArrayList<>();
        PerceptionTools perception = new PerceptionTools();
        check(p, NumenTools.tool(perception, "get_self_status"), new GetSelfStatusTool());
        check(p, NumenTools.tool(perception, "inspect_block"), new InspectBlockTool());
        check(p, NumenTools.tool(perception, "get_owner_status"), new GetOwnerStatusTool());
        check(p, NumenTools.tool(perception, "get_world_info"), new GetWorldInfoTool());

        check(p, NumenTools.tool(new MovementTools(), "move_to"), new MoveToTool());

        CombatTools combat = new CombatTools();
        check(p, NumenTools.tool(combat, "hunt"), new HuntTool());
        check(p, NumenTools.tool(combat, "shoot"), new ShootTool());

        LocateTools locate = new LocateTools();
        check(p, NumenTools.tool(locate, "locate_structure"), new LocateStructureTool());
        check(p, NumenTools.tool(locate, "locate_biome"), new LocateBiomeTool());

        InventoryTools inventory = new InventoryTools();
        check(p, NumenTools.tool(inventory, "collect_items"), new CollectItemsTool());
        check(p, NumenTools.tool(inventory, "equip_item"), new EquipTool());
        check(p, NumenTools.tool(inventory, "eat_item"), new EatItemTool());
        check(p, NumenTools.tool(inventory, "wait"), new WaitTool());
        check(p, NumenTools.tool(inventory, "drop_items"), new DropItemsTool());

        BlockActionTools blocks = new BlockActionTools();
        check(p, NumenTools.tool(blocks, "auto_mine"), new MineBlockTool());
        check(p, NumenTools.tool(blocks, "place_block"), new PlaceBlockTool());
        check(p, NumenTools.tool(blocks, "break_block"), new BreakBlockTool());
        check(p, NumenTools.tool(blocks, "interact_at"), new InteractAtTool());
        check(p, NumenTools.tool(blocks, "interact_entity"), new InteractEntityTool());

        GuiTools gui = new GuiTools();
        check(p, NumenTools.tool(gui, "inspect_gui"), new InspectGuiTool());
        check(p, NumenTools.tool(gui, "close_gui"), new CloseGuiTool());

        QueryExtraTools queries = new QueryExtraTools();
        check(p, NumenTools.tool(queries, "lookup_recipe"), new LookupRecipeTool());
        check(p, NumenTools.tool(queries, "scan_nearby_entities"), new ScanNearbyEntitiesTool());
        check(p, NumenTools.tool(queries, "inspect_block_storage"), new InspectBlockStorageTool());

        check(p, NumenTools.tool(new ScanTools(), "scan_blocks"), new ScanBlocksTool());
        check(p, NumenTools.tool(new AgentTools(), "load_skill"), new LoadSkillTool());

        assertTrue(p.isEmpty(), "surface mismatches (" + p.size() + "):\n" + String.join("\n", p));
    }

    @Test
    void moveToBuildsRecordFromCoercedArgs() {
        NumenTool migrated = NumenTools.tool(new MovementTools(), "move_to");

        // x + z, no y → a COLUMN goal; speed coerced; toolCallId + deadline injected.
        JsonObject args = new JsonObject();
        args.addProperty("x", 10.0);
        args.addProperty("z", 20.0);
        args.addProperty("speed", 1.5);
        // y intentionally absent → nullable, must coerce to null.

        TaskRecord rec = migrated.toTaskRecord("call-1", args, 1000L);
        MoveToTaskRecord move = assertInstanceOf(MoveToTaskRecord.class, rec);

        assertEquals(Double.valueOf(10.0), move.x);
        assertNull(move.y, "absent nullable arg must coerce to null");
        assertEquals(Double.valueOf(20.0), move.z);
        assertEquals(1.5, move.speed);
        assertEquals(MoveToTaskRecord.Kind.COLUMN, move.kind);
        assertEquals("call-1", move.getToolCallId());
        assertEquals(1000L + 30 * 20, move.getDeadlineGameTime(), "deadline = now + timeoutTicks");
    }

    /** The LOCAL path runs client-side: load_skill executes and reports a missing skill. */
    @Test
    void localToolRunsClientSide() {
        NumenTool loadSkill = NumenTools.tool(new AgentTools(), "load_skill");
        JsonObject args = new JsonObject();
        args.addProperty("name", "definitely_missing_skill_xyz");
        String result = loadSkill.executeLocal(args, null);   // load_skill needs no ClientToolContext
        assertTrue(result.contains("unknown skill"), "local tool ran and reported the missing skill");
    }

    /** A list arg binds to {@code List<String>} and a truly-optional arg binds to null — MC-free. */
    @Test
    void listAndOptionalArgsCoerce() {
        NumenTool probe = NumenTools.tool(new ListProbe(), "list_probe");

        JsonObject args = new JsonObject();
        JsonArray ids = new JsonArray();
        ids.add("a");
        ids.add("b");
        args.add("ids", ids);
        assertEquals("2/null", probe.executeQuery(args, null), "list coerced; optional arg → null");

        args.addProperty("n", 7);
        assertEquals("2/7", probe.executeQuery(args, null), "optional arg present → value");
    }

    /** Minimal holder exercising List + optional coercion without touching Minecraft. */
    static final class ListProbe {
        @NumenAction(name = "list_probe", description = "probe")
        public String probe(@Arg("ids") List<String> ids,
                            @Arg(value = "n", required = false) Integer n,
                            NumenPlayer self) {
            return ids.size() + "/" + (n == null ? "null" : n);
        }
    }
}
