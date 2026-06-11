package com.dwinovo.animus.client.agent;

import com.dwinovo.animus.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side registry of companions this player has interacted with — the
 * data source for the roster panel (hotkey) and the remote chat entry point.
 *
 * <h2>Why client memory, not a server query</h2>
 * The brain, conversations, and work-block memory are all client-side and
 * keyed by entity UUID; the roster completes the set with the one fact the
 * client otherwise forgets between sessions: <em>which UUIDs are my pets and
 * what are they called</em>. A pet is enrolled the first time the owner
 * right-clicks it (physical first contact, same as taming) and dropped when
 * its death payload arrives. The deliberate limitation: a fresh client
 * install doesn't know your pets until you right-click each once.
 *
 * <h2>Persistence</h2>
 * {@code config/animus/roster.json}, write-through. Client main thread only.
 */
public final class AnimusRoster {

    /** One known companion: identity + display name + recency for sorting. */
    public record Entry(UUID uuid, String name, long lastSeenMs) {}

    private static AnimusRoster instance;

    private final Path file;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    private AnimusRoster(Path file) {
        this.file = file;
        load();
    }

    public static AnimusRoster instance() {
        if (instance == null) {
            instance = new AnimusRoster(Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("animus").resolve("roster.json"));
        }
        return instance;
    }

    /** Enroll or refresh a companion (called on every GUI open via right-click). */
    public void record(UUID uuid, String name) {
        entries.put(uuid, new Entry(uuid, name, System.currentTimeMillis()));
        save();
    }

    /** Drop a companion (death payload). */
    public void remove(UUID uuid) {
        if (entries.remove(uuid) != null) save();
    }

    /** All known companions, most recently interacted first. */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>(entries.values());
        out.sort(Comparator.comparingLong(Entry::lastSeenMs).reversed());
        return out;
    }

    public int size() {
        return entries.size();
    }

    // ---- persistence ----

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                UUID uuid = UUID.fromString(o.get("uuid").getAsString());
                entries.put(uuid, new Entry(uuid,
                        o.get("name").getAsString(),
                        o.has("last") ? o.get("last").getAsLong() : 0L));
            }
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[animus-roster] failed to load {}: {}", file, ex.toString());
        }
    }

    private void save() {
        JsonArray arr = new JsonArray();
        for (Entry e : entries.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", e.uuid().toString());
            o.addProperty("name", e.name());
            o.addProperty("last", e.lastSeenMs());
            arr.add(o);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, arr.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[animus-roster] failed to save {}: {}", file, ex.toString());
        }
    }
}
