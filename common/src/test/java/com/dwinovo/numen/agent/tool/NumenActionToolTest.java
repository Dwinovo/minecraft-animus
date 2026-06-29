package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.tool.api.Arg;
import com.dwinovo.numen.agent.tool.api.NumenAction;
import com.dwinovo.numen.agent.tool.tools.*;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the {@code @NumenAction} tools. The full LLM-facing
 * surface (name, description, parameter schema) of every registered tool is
 * captured in {@code src/test/resources/numen_tool_snapshot.txt} and asserted
 * byte-for-byte; a holder change that shifts what the model sees fails the test
 * (regenerate intentionally by deleting the file or passing
 * {@code -Dnumen.snapshot.update}). Runtime tests then exercise argument
 * coercion through the real {@link NumenTool#invoke} path for client tools.
 */
class NumenActionToolTest {

    private static final Path SNAPSHOT = Path.of("src/test/resources/numen_tool_snapshot.txt");

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** Every registered @NumenAction tool, built through the adapter. */
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
        return s.replace("\r\n", "\n");
    }

    /** Run a client-side (LOCAL) tool through the real {@link NumenTool#invoke} path and return its result. */
    private static String runLocal(NumenTool tool, JsonObject args) {
        String[] box = {null};
        ToolCall call = new ToolCall("t", tool.name(), args.toString(),
                new ClientToolContext(null, null),
                json -> box[0] = json,
                () -> { throw new AssertionError("a local tool must not ship to the server"); });
        tool.invoke(call);
        return box[0];
    }

    @Test
    void checkArgsValidatesWithoutExecuting() {
        NumenTool moveTo = NumenTools.tool(new MovementTools(), "move_to");
        JsonObject good = new JsonObject();
        good.addProperty("x", 1.0);
        good.addProperty("z", 2.0);
        good.addProperty("speed", 1.0);   // y nullable → absent is fine
        moveTo.checkArgs(good);

        JsonObject bad = new JsonObject();
        bad.addProperty("x", 1.0);
        bad.addProperty("z", 2.0);        // speed required + non-nullable → missing
        assertThrows(IllegalArgumentException.class, () -> moveTo.checkArgs(bad));
    }

    @Test
    void localToolRunsClientSide() {
        JsonObject args = new JsonObject();
        args.addProperty("name", "definitely_missing_skill_xyz");
        String result = runLocal(NumenTools.tool(new AgentTools(), "load_skill"), args);
        assertTrue(result.contains("unknown skill"), "local tool ran and reported the missing skill");
    }

    @Test
    void objectArrayArgCoerces() {
        JsonObject args = new JsonObject();
        JsonArray todos = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("content", "dig iron");
        item.addProperty("status", "in_progress");
        item.addProperty("priority", "high");
        todos.add(item);
        args.add("todos", todos);

        String result = runLocal(NumenTools.tool(new AgentTools(), "todowrite"), args);
        assertTrue(result.contains("\"success\":true"), "object-array bound and tool ran");
        assertTrue(result.contains("dig iron"), "todo content echoed back");
    }

    @Test
    void listAndOptionalArgsCoerce() {
        NumenTool probe = NumenTools.tool(new ListProbe(), "list_probe");

        JsonObject args = new JsonObject();
        JsonArray ids = new JsonArray();
        ids.add("a");
        ids.add("b");
        args.add("ids", ids);
        assertEquals("2/null", runLocal(probe, args), "list coerced; optional arg → null");

        args.addProperty("n", 7);
        assertEquals("2/7", runLocal(probe, args), "optional arg present → value");
    }

    /** Minimal LOCAL holder exercising List + optional coercion without touching Minecraft. */
    static final class ListProbe {
        @NumenAction(name = "list_probe", description = "probe")
        public String probe(@Arg("ids") List<String> ids,
                            @Arg(value = "n", required = false) Integer n) {
            return ids.size() + "/" + (n == null ? "null" : n);
        }
    }
}
