package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-entity memory of functional blocks the Numen has used or placed —
 * crafting tables, furnaces, chests, … Injected into the system prompt every
 * turn as a {@code <known_blocks>} XML section so the model walks back to
 * infrastructure it already owns instead of crafting and placing duplicates
 * (placed tables/furnaces deliberately stay in the world for exactly this).
 *
 * <h2>How entries get here</h2>
 * Harvested by {@link EntityAgentLoop} from successful tool results: a
 * place_block of a tracked type reports the block it placed, and an interact_at
 * that opens a station reports the block it activated. No new tooling — the
 * results already carried the data; this just stops forgetting it.
 *
 * <h2>Self-healing</h2>
 * The world changes behind our back (owner mines the furnace, a creeper
 * removes the area). On every {@link #formatXml} the entries in currently
 * loaded chunks are re-checked against the actual block state and stale ones
 * evicted. Entries in unloaded chunks are kept on faith — distance is not
 * evidence of absence.
 *
 * <h2>Persistence</h2>
 * One small JSON file per entity at
 * {@code config/numen/memory/<uuid>.blocks.json}, write-through on change.
 * Client main thread only, like everything in this package.
 */
public final class WorkBlockMemory {

    /** Block id paths worth remembering — interaction infrastructure, not decoration. */
    private static final Set<String> TRACKED_TYPES = Set.of(
            "crafting_table", "furnace", "blast_furnace", "smoker",
            "chest", "barrel", "ender_chest",
            "anvil", "chipped_anvil", "damaged_anvil",
            "grindstone", "stonecutter", "smithing_table",
            "enchanting_table", "brewing_stand", "lodestone");

    /** Cap on remembered blocks: oldest-touched entries fall off first. */
    private static final int MAX_ENTRIES = 16;

    private final Path file;
    /** packed BlockPos → block id path; insertion order = recency (refreshed on record). */
    private final LinkedHashMap<Long, String> blocks = new LinkedHashMap<>();

    private WorkBlockMemory(Path file) {
        this.file = file;
        load();
    }

    public static WorkBlockMemory forEntity(Path memoryDir, UUID entityUuid) {
        return new WorkBlockMemory(memoryDir.resolve(entityUuid + ".blocks.json"));
    }

    /** Is this block id path a type we remember at all? */
    public static boolean isTracked(String blockPath) {
        return TRACKED_TYPES.contains(blockPath);
    }

    /** Remember (or refresh the recency of) a tracked block. Untracked types are ignored. */
    public void record(String blockPath, BlockPos pos) {
        if (!isTracked(blockPath)) return;
        long key = pos.asLong();
        String prev = blocks.remove(key);      // re-insert → newest
        blocks.put(key, blockPath);
        while (blocks.size() > MAX_ENTRIES) {
            Iterator<Long> it = blocks.keySet().iterator();
            it.next();
            it.remove();
        }
        if (!blockPath.equals(prev)) {
            Constants.LOG.info("[numen-memory] remembered {} at {},{},{}",
                    blockPath, pos.getX(), pos.getY(), pos.getZ());
        }
        save();
    }

    /**
     * Build the {@code <known_blocks>} system-prompt section, validating entries
     * against loaded chunks first (stale → evicted). Returns the empty string
     * when nothing is known, so callers can append unconditionally.
     *
     * @param level the entity's level for validation, or {@code null} when the
     *              body isn't resolvable this turn (entries kept on faith)
     */
    public String formatXml(Level level) {
        boolean changed = false;
        if (level != null) {
            Iterator<Map.Entry<Long, String>> it = blocks.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, String> e = it.next();
                BlockPos pos = BlockPos.of(e.getKey());
                if (!level.hasChunkAt(pos)) continue;   // unloaded — can't verify, keep
                String actual = BuiltInRegistries.BLOCK
                        .getKey(level.getBlockState(pos).getBlock()).getPath();
                if (!actual.equals(e.getValue())) {
                    Constants.LOG.info("[numen-memory] forgot {} at {},{},{} (now {})",
                            e.getValue(), pos.getX(), pos.getY(), pos.getZ(), actual);
                    it.remove();
                    changed = true;
                }
            }
        }
        if (changed) save();
        if (blocks.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(128);
        sb.append("<known_blocks>\n");
        sb.append("  Functional blocks you have already placed or used. Walk back to these ")
          .append("instead of crafting/placing duplicates:\n");
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            BlockPos pos = BlockPos.of(e.getKey());
            sb.append("  <block type=\"").append(e.getValue())
              .append("\" x=\"").append(pos.getX())
              .append("\" y=\"").append(pos.getY())
              .append("\" z=\"").append(pos.getZ()).append("\"/>\n");
        }
        sb.append("</known_blocks>");
        return sb.toString();
    }

    // ---- persistence (tiny write-through JSON file) ----

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                BlockPos pos = new BlockPos(o.get("x").getAsInt(),
                        o.get("y").getAsInt(), o.get("z").getAsInt());
                blocks.put(pos.asLong(), o.get("type").getAsString());
            }
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-memory] failed to load {}: {}", file, ex.toString());
        }
    }

    private void save() {
        JsonArray arr = new JsonArray();
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            BlockPos pos = BlockPos.of(e.getKey());
            JsonObject o = new JsonObject();
            o.addProperty("type", e.getValue());
            o.addProperty("x", pos.getX());
            o.addProperty("y", pos.getY());
            o.addProperty("z", pos.getZ());
            arr.add(o);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, arr.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-memory] failed to save {}: {}", file, ex.toString());
        }
    }

    /** Visible for the GUI / debug: current entries as readable lines. */
    public List<String> describeAll() {
        List<String> out = new ArrayList<>(blocks.size());
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            BlockPos pos = BlockPos.of(e.getKey());
            out.add(e.getValue() + " @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        }
        return out;
    }
}
