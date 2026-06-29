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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the {@code @NumenAction} tools. The hand-written oracle
 * classes are gone, so the guard is now a golden snapshot: the full LLM-facing
 * surface (name, category, description, parameter schema) of every registered
 * tool is captured in {@code src/test/resources/numen_tool_snapshot.txt} and
 * asserted byte-for-byte. A change to any holder that alters what the model
 * sees fails this test; if the change is intentional, regenerate the snapshot
 * with {@code -Dnumen.snapshot.update}.
 */
class NumenActionToolTest {

    private static final Path SNAPSHOT = Path.of("src/test/resources/numen_tool_snapshot.txt");

    @BeforeAll
    static void bootstrapMinecraft() {
        // A few tools touch registries at class-init / schema build; bring them up.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** Every registered @NumenAction tool, built through the adapter (registration order irrelevant). */
    private static List<NumenTool> allTools() {
        PerceptionTools perception = new PerceptionTools();
        CombatTools combat = new CombatTools();
        LocateTools locate = new LocateTools();
        InventoryTools inventory = new InventoryTools();
        BlockActionTools blocks = new BlockActionTools();
        GuiTools gui = new GuiTools();
        QueryExtraTools queries = new QueryExtraTools();
        AgentTools agent = new AgentTools();
        return List.of(
                NumenTools.tool(perception, "get_self_status"),
                NumenTools.tool(perception, "inspect_block"),
                NumenTools.tool(perception, "get_owner_status"),
                NumenTools.tool(perception, "get_world_info"),
                NumenTools.tool(new MovementTools(), "move_to"),
                NumenTools.tool(combat, "hunt"),
                NumenTools.tool(combat, "shoot"),
                NumenTools.tool(locate, "locate_structure"),
                NumenTools.tool(locate, "locate_biome"),
                NumenTools.tool(inventory, "collect_items"),
                NumenTools.tool(inventory, "equip_item"),
                NumenTools.tool(inventory, "eat_item"),
                NumenTools.tool(inventory, "wait"),
                NumenTools.tool(inventory, "drop_items"),
                NumenTools.tool(blocks, "auto_mine"),
                NumenTools.tool(blocks, "place_block"),
                NumenTools.tool(blocks, "break_block"),
                NumenTools.tool(blocks, "interact_at"),
                NumenTools.tool(blocks, "interact_entity"),
                NumenTools.tool(gui, "inspect_gui"),
                NumenTools.tool(gui, "close_gui"),
                NumenTools.tool(queries, "lookup_recipe"),
                NumenTools.tool(queries, "scan_nearby_entities"),
                NumenTools.tool(queries, "inspect_block_storage"),
                NumenTools.tool(new ScanTools(), "scan_blocks"),
                NumenTools.tool(agent, "load_skill"),
                NumenTools.tool(agent, "todowrite"),
                NumenTools.tool(new ContainerTools(), "transfer"));
    }

    @Test
    void toolSurfaceMatchesSnapshot() throws Exception {
        List<NumenTool> tools = new ArrayList<>(allTools());
        tools.sort(Comparator.comparing(NumenTool::name));
        StringBuilder sb = new StringBuilder();
        for (NumenTool t : tools) {
            sb.append(snapshotLine(t)).append("\n\n");
        }
        String actual = normalize(sb.toString());

        // Create the golden on first run (file absent); regenerate intentionally by
        // deleting it or passing -Dnumen.snapshot.update. Otherwise compare.
        boolean missing = !Files.exists(SNAPSHOT);
        if (missing || Boolean.getBoolean("numen.snapshot.update")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, actual);
            System.out.println("[snapshot] wrote " + SNAPSHOT.toAbsolutePath()
                    + (missing ? " (created)" : " (updated)"));
            return;
        }
        String expected = normalize(Files.readString(SNAPSHOT));
        assertEquals(expected, actual,
                "tool surface drifted from snapshot; if intentional, regenerate with -Dnumen.snapshot.update");
    }

    private static String snapshotLine(NumenTool t) {
        return "=== " + t.name() + " ===\n"
                + "query=" + t.isQuery() + " local=" + t.isLocal() + " async=" + t.isAsyncQuery() + "\n"
                + "desc: " + t.description() + "\n"
                + "schema: " + canonical(t.parameterSchema());
    }

    /** Stable, order-independent rendering of a schema map (keys sorted recursively). */
    private static String canonical(Object node) {
        if (node instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparing(e -> String.valueOf(e.getKey())))
                    .map(e -> e.getKey() + "=" + canonical(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (node instanceof List<?> list) {
            return list.stream().map(NumenActionToolTest::canonical).collect(Collectors.joining(", ", "[", "]"));
        }
        return String.valueOf(node);
    }

    private static String normalize(String s) {
        return s.replace("\r\n", "\n");   // CRLF-proof the comparison
    }

    @Test
    void moveToBuildsRecordFromCoercedArgs() {
        NumenTool migrated = NumenTools.tool(new MovementTools(), "move_to");

        // x + z, no y → a COLUMN goal; speed coerced; toolCallId + deadline injected.
        JsonObject args = new JsonObject();
        args.addProperty("x", 10.0);
        args.addProperty("z", 20.0);
        args.addProperty("speed", 1.5);

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
        String result = loadSkill.executeLocal(args, null);
        assertTrue(result.contains("unknown skill"), "local tool ran and reported the missing skill");
    }

    /** An object-array arg binds to {@code List<record>}; exercised via todowrite's LOCAL path. */
    @Test
    void objectArrayArgCoerces() {
        NumenTool todo = NumenTools.tool(new AgentTools(), "todowrite");
        JsonObject args = new JsonObject();
        JsonArray todos = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("content", "dig iron");
        item.addProperty("status", "in_progress");
        item.addProperty("priority", "high");
        todos.add(item);
        args.add("todos", todos);

        String result = todo.executeLocal(args, null);
        assertTrue(result.contains("\"success\":true"), "object-array bound and tool ran");
        assertTrue(result.contains("dig iron"), "todo content echoed back");
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
