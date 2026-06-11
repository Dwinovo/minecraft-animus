package com.dwinovo.animus.agent.llm;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.agent.provider.AssistantTurn;
import com.dwinovo.animus.agent.provider.LlmToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Append-only JSONL persistence for one entity's conversation —
 * {@code <gameDir>/config/animus/conversations/<entity-uuid>.jsonl}, one
 * message per line. The entity UUID is globally unique and dimension-stable,
 * so no per-world scoping is needed; the same file follows the companion for
 * its whole life.
 *
 * <h2>Why JSONL, not a database</h2>
 * The access pattern is: single writer (client main thread), append per
 * message, read-once on load, no queries. Append-only text is crash-safe
 * (a hard kill loses at most the final line — {@link #load} skips a torn
 * tail), the file is player-readable/editable like the rest of
 * {@code config/animus}, and Gson ships with Minecraft — zero dependencies.
 *
 * <h2>Tail loading</h2>
 * History on disk is unbounded, but what gets replayed into the LLM context
 * is not: {@link #load} returns roughly the last {@link #DEFAULT_LOAD_LIMIT}
 * messages, extended backwards to the nearest {@code user} message so the
 * slice never opens with an orphan tool result or a mid-chain assistant turn
 * (backends 400 on those). Full history stays on disk for a future
 * summarising memory layer.
 *
 * <h2>Best-effort by design</h2>
 * IO failures log a warning and the chat carries on in memory — persistence
 * must never take the companion offline.
 */
public final class ConvoLog {

    /** Soft cap on messages replayed into context (the file itself is unbounded). */
    public static final int DEFAULT_LOAD_LIMIT = 200;

    private final Path file;

    private ConvoLog(Path file) {
        this.file = file;
    }

    /** The log for one entity under {@code conversationsDir}. Creates nothing until the first append. */
    public static ConvoLog forEntity(Path conversationsDir, UUID entityUuid) {
        return new ConvoLog(conversationsDir.resolve(entityUuid + ".jsonl"));
    }

    public Path file() {
        return file;
    }

    // ---- write ----

    /** Append one message as a single JSONL line. Best-effort: failures only warn. */
    public void append(ConvoState.Msg msg) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, encode(msg).toString() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            Constants.LOG.warn("[animus-convo] failed to append to {}: {}", file, ex.toString());
        }
    }

    /**
     * Append a compaction boundary: a {@code role:"compact"} line whose content
     * is the (already wrapped) summary that replaces everything before it. The
     * file stays append-only — the full pre-compaction history remains on disk
     * as an archive, but {@link #load} starts fresh from the latest boundary,
     * so relaunches replay the compacted view, not the raw past.
     */
    public void appendCompactSummary(String wrappedSummary) {
        JsonObject o = new JsonObject();
        o.addProperty("role", "compact");
        o.addProperty("content", wrappedSummary);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            Constants.LOG.warn("[animus-convo] failed to append compact boundary to {}: {}",
                    file, ex.toString());
        }
    }

    /** Remove the file (conversation reset). */
    public void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            Constants.LOG.warn("[animus-convo] failed to delete {}: {}", file, ex.toString());
        }
    }

    // ---- read ----

    /**
     * Load the protocol-valid tail of the conversation: parse every line
     * (skipping torn/corrupt ones with a warning), take the last
     * {@code limit} messages, then extend backwards to the nearest
     * {@code user} message so the slice starts at a turn boundary.
     */
    public List<ConvoState.Msg> load(int limit) {
        if (!Files.isRegularFile(file)) return List.of();

        List<ConvoState.Msg> all = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    if ("compact".equals(str(o.get("role")))) {
                        // Compaction boundary: everything before it was replaced
                        // by this summary. Restart the replay from here.
                        all.clear();
                        all.add(new ConvoState.Msg.User(str(o.get("content"))));
                        continue;
                    }
                    all.add(decode(o));
                } catch (RuntimeException ex) {
                    // Torn tail from a hard kill, or hand-edited damage: drop the
                    // line, keep the rest. (A torn line is by construction last,
                    // but we tolerate damage anywhere.)
                    Constants.LOG.warn("[animus-convo] skipping unparsable line in {}: {}",
                            file.getFileName(), ex.toString());
                }
            }
        } catch (IOException ex) {
            Constants.LOG.warn("[animus-convo] failed to read {}: {}", file, ex.toString());
            return List.of();
        }
        if (all.size() <= limit) return all;

        // Tail-trim, then walk back to the nearest user message: a slice that
        // opens with a tool result (orphan id) or mid-chain assistant turn is
        // rejected by the API. A conversation always begins with a user
        // message, so this terminates.
        int start = all.size() - limit;
        while (start > 0 && !(all.get(start) instanceof ConvoState.Msg.User)) {
            start--;
        }
        List<ConvoState.Msg> tail = all.subList(start, all.size());
        Constants.LOG.info("[animus-convo] loaded {}/{} msgs from {}",
                tail.size(), all.size(), file.getFileName());
        return new ArrayList<>(tail);
    }

    /**
     * Tool-call ids in {@code history} that have no matching tool result —
     * the signature of a session killed mid-task. The caller synthesizes
     * "interrupted" results for these (same trick as the owner-interrupt
     * path) or the next request 400s.
     */
    public static List<String> unansweredToolCallIds(List<ConvoState.Msg> history) {
        Set<String> answered = new HashSet<>();
        for (ConvoState.Msg msg : history) {
            if (msg instanceof ConvoState.Msg.Tool t) answered.add(t.toolCallId());
        }
        List<String> unanswered = new ArrayList<>();
        for (ConvoState.Msg msg : history) {
            if (msg instanceof ConvoState.Msg.Assistant a) {
                for (LlmToolCall tc : a.turn().toolCalls()) {
                    if (!answered.contains(tc.id())) unanswered.add(tc.id());
                }
            }
        }
        return unanswered;
    }

    // ---- codec (one JSONL line per Msg, OpenAI-flavoured field names) ----

    private static JsonObject encode(ConvoState.Msg msg) {
        JsonObject o = new JsonObject();
        switch (msg) {
            case ConvoState.Msg.User u -> {
                o.addProperty("role", "user");
                o.addProperty("content", u.content());
            }
            case ConvoState.Msg.Assistant a -> {
                o.addProperty("role", "assistant");
                o.addProperty("content", a.turn().content());
                if (a.turn().hasToolCalls()) {
                    JsonArray calls = new JsonArray();
                    for (LlmToolCall tc : a.turn().toolCalls()) {
                        JsonObject c = new JsonObject();
                        c.addProperty("id", tc.id());
                        c.addProperty("name", tc.name());
                        c.addProperty("arguments", tc.arguments());
                        calls.add(c);
                    }
                    o.add("tool_calls", calls);
                }
                // Provider extras (e.g. DeepSeek reasoning_content) must survive
                // the round-trip — they are required on the next request.
                if (!a.turn().extras().entrySet().isEmpty()) {
                    o.add("extras", a.turn().extras());
                }
            }
            case ConvoState.Msg.Tool t -> {
                o.addProperty("role", "tool");
                o.addProperty("tool_call_id", t.toolCallId());
                o.addProperty("content", t.content());
            }
        }
        return o;
    }

    private static ConvoState.Msg decode(JsonObject o) {
        String role = str(o.get("role"));
        return switch (role) {
            case "user" -> new ConvoState.Msg.User(str(o.get("content")));
            case "tool" -> new ConvoState.Msg.Tool(str(o.get("tool_call_id")), str(o.get("content")));
            case "assistant" -> {
                List<LlmToolCall> calls = new ArrayList<>();
                if (o.has("tool_calls") && o.get("tool_calls").isJsonArray()) {
                    for (JsonElement el : o.getAsJsonArray("tool_calls")) {
                        JsonObject c = el.getAsJsonObject();
                        calls.add(new LlmToolCall(
                                str(c.get("id")), str(c.get("name")), str(c.get("arguments"))));
                    }
                }
                JsonObject extras = o.has("extras") && o.get("extras").isJsonObject()
                        ? o.getAsJsonObject("extras") : null;
                yield new ConvoState.Msg.Assistant(new AssistantTurn(str(o.get("content")), calls, extras));
            }
            default -> throw new IllegalArgumentException("unknown role: " + role);
        };
    }

    private static String str(JsonElement el) {
        return el == null || el.isJsonNull() ? "" : el.getAsString();
    }
}
