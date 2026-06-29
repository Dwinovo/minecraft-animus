package com.dwinovo.numen.bench;

import com.dwinovo.numen.CommonClass;
import com.dwinovo.numen.agent.prompt.NumenPrompts;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmProvider;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.tool.NumenActionTool;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.agent.http.HttpLlmTransport;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Offline tool-call benchmark engine (L1): replays a frozen context through the
 * REAL harness — the same tool schemas ({@link ToolRegistry}), the same system
 * prompt ({@link NumenPrompts}), the same wire-format ({@link LlmProvider}) the
 * live loop uses — then scores the model's tool choice and arguments. No
 * Minecraft world, no companion body: the only live dependency is the LLM call,
 * so a run is seconds and reproducible.
 *
 * <p>What it measures is exactly what prompt/schema edits (P1–P3) move: given a
 * situation, does the model pick the right tool with valid, correct arguments.
 * Tool <em>execution</em> (does move_to pathfind) is a separate concern tested
 * elsewhere.
 *
 * <p>Config comes from env vars / system properties (see {@link Config}); the
 * JUnit entry point skips when no API key is present, so a plain {@code test}
 * run never makes a network call.
 */
public final class ToolBench {

    private ToolBench() {}

    // ---- configuration (env var or -Dnumen.bench.* system property) ----

    public static final class Config {
        public final String apiKey;
        public final String baseUrl;
        public final String model;
        public final String providerName;
        public final String proxy;
        public final int samples;
        public final Double temperature;

        private Config(String apiKey, String baseUrl, String model, String providerName,
                       String proxy, int samples, Double temperature) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
            this.providerName = providerName;
            this.proxy = proxy;
            this.samples = samples;
            this.temperature = temperature;
        }

        public static Config fromEnv() {
            String key = get("api_key", "NUMEN_BENCH_API_KEY", null);
            String base = get("base_url", "NUMEN_BENCH_BASE_URL", "");
            String model = get("model", "NUMEN_BENCH_MODEL", "gpt-4o-mini");
            String prov = get("provider", "NUMEN_BENCH_PROVIDER", "openai");
            String proxy = get("proxy", "NUMEN_BENCH_PROXY", "");
            int samples = parseInt(get("samples", "NUMEN_BENCH_SAMPLES", "5"), 5);
            String t = get("temperature", "NUMEN_BENCH_TEMPERATURE", "");
            Double temp = t.isBlank() ? null : parseDouble(t);
            return new Config(key, base, model, prov, proxy, samples, temp);
        }

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** Read {@code -Dnumen.bench.<prop>} first, then {@code <ENV>}, then default. */
        private static String get(String prop, String env, String def) {
            String sys = System.getProperty("numen.bench." + prop);
            if (sys != null && !sys.isBlank()) return sys;
            String e = System.getenv(env);
            if (e != null && !e.isBlank()) return e;
            return def;
        }
    }

    // ---- frozen case model (loaded from src/test/resources/bench/*.json via Gson) ----

    public static final class BenchCase {
        public String name;
        public String category;         // bucket: simple | mid-trajectory | negative | …  (for per-group reporting)
        public String request;          // the owner's final message
        public EnvSpec env;             // optional frozen <env>
        public List<KnownBlock> known_blocks; // optional frozen <known_blocks>
        public List<RawMsg> prior;      // optional prior conversation turns
        public Expect expect;
        public String note;

        public String bucket() { return category == null || category.isBlank() ? "uncategorized" : category; }
    }

    public static final class EnvSpec {
        public String dimension;
        public String owner_name;
    }

    public static final class KnownBlock {
        public String type;
        public int x, y, z;
    }

    /** A raw prior message: role=user|assistant|tool (+ tool_call fields for assistant/tool). */
    public static final class RawMsg {
        public String role;
        public String content;
        public String tool_call_id;
        public String name;
        public String arguments;
        public String reasoning;   // optional: assistant reasoning_content (thinking models require it echoed back)
    }

    public static final class Expect {
        public List<String> anyOf;                 // acceptable first-call tool names (the acceptable-set)
        public Map<String, JsonObject> args;       // optional key-arg checks, keyed by tool name;
                                                    //   a param value may be a single value OR an array
                                                    //   of acceptable values (any match passes)
        public boolean noCall;                     // negative case: the correct behavior is to call NO tool
    }

    // ---- one scored sample / aggregated case result ----

    public record Sample(String calledTool, boolean selectionOk, boolean argsValid,
                         Boolean argsMatch, String detail) {}

    public record CaseResult(BenchCase c, List<Sample> samples) {
        public double selectionRate() { return rate(Sample::selectionOk); }
        public double argsValidRate() { return rate(Sample::argsValid); }
        /** pass^k reliability: did EVERY sample pick an acceptable tool? (1 = rock-solid). */
        public boolean allSelectionPassed() {
            return !samples.isEmpty() && samples.stream().allMatch(Sample::selectionOk);
        }
        public String bucket() { return c.bucket(); }
        /** null when the case declares no expected args. */
        public Double argsMatchRate() {
            List<Sample> withMatch = samples.stream().filter(s -> s.argsMatch() != null).toList();
            if (withMatch.isEmpty()) return null;
            long ok = withMatch.stream().filter(s -> Boolean.TRUE.equals(s.argsMatch())).count();
            return (double) ok / withMatch.size();
        }
        private double rate(java.util.function.Predicate<Sample> p) {
            if (samples.isEmpty()) return 0;
            return (double) samples.stream().filter(p).count() / samples.size();
        }
    }

    // ---- engine ----

    public static List<NumenTool> tools() {
        if (ToolRegistry.size() == 0) {
            CommonClass.registerTools();
        }
        return ToolRegistry.all();
    }

    /**
     * Resolve the wire-format provider — delegates to the mod's own
     * {@link NumenLlmClient#pickProvider} so the benchmark speaks to each backend
     * exactly like the live loop does (same aliases: kimi/doubao/qwen/glm/…, same
     * OpenAI-compatible fallback), instead of keeping a second copy that can drift.
     */
    public static LlmProvider provider(Config cfg) {
        return NumenLlmClient.pickProvider(cfg.providerName);
    }

    /** Compose the exact system prompt the live loop builds, with frozen env/known_blocks. */
    public static String systemPrompt(BenchCase c) {
        StringBuilder sb = new StringBuilder(NumenPrompts.ENTITY_PROMPT);
        String env = renderEnv(c.env);
        if (env != null) sb.append("\n\n").append(env);
        String kb = renderKnownBlocks(c.known_blocks);
        if (!kb.isEmpty()) sb.append("\n\n").append(kb);
        return sb.toString();
    }

    /** Run all k samples for one case against the live model and score each. */
    public static CaseResult runCase(BenchCase c, Config cfg, LlmProvider provider,
                                     HttpLlmTransport transport, String url, List<NumenTool> tools) {
        JsonArray toolList = provider.buildToolList(tools);
        List<JsonObject> wire = wireMessages(c, provider);
        List<Sample> samples = new ArrayList<>(cfg.samples);
        for (int i = 0; i < cfg.samples; i++) {
            samples.add(runSample(c, cfg, provider, transport, url, wire, toolList));
        }
        return new CaseResult(c, samples);
    }

    private static Sample runSample(BenchCase c, Config cfg, LlmProvider provider,
                                    HttpLlmTransport transport, String url,
                                    List<JsonObject> wire, JsonArray toolList) {
        try {
            JsonObject body = provider.buildRequestBody(cfg.model, systemPrompt(c), wire, toolList);
            if (cfg.temperature != null) body.addProperty("temperature", cfg.temperature);
            JsonObject resp = transport.post(url, cfg.apiKey, body)
                    .get(150, java.util.concurrent.TimeUnit.SECONDS);
            AssistantTurn turn = provider.parseResponseBody(resp);
            return score(c, turn);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return new Sample(null, false, false, null,
                    "ERROR: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    /** Score one assistant turn against the case's expectation. */
    static Sample score(BenchCase c, AssistantTurn turn) {
        List<LlmToolCall> calls = turn.toolCalls();

        // Negative case: the right move is to NOT call a tool (chit-chat, clarify, impossible).
        // Over-triggering — calling anything — is the failure we want to catch here.
        if (c.expect != null && c.expect.noCall) {
            boolean held = calls.isEmpty();
            return new Sample(held ? null : calls.get(0).name(), held, true, null,
                    held ? "held off → \"" + truncate(turn.content(), 50) + "\""
                         : "WRONGLY called " + names(calls));
        }

        if (calls.isEmpty()) {
            return new Sample(null, false, false,
                    c.expect != null && c.expect.args != null ? Boolean.FALSE : null,
                    "no tool call — text: " + truncate(turn.content(), 80));
        }
        LlmToolCall first = calls.get(0);
        String firstName = first.name();

        boolean selectionOk = c.expect == null || c.expect.anyOf == null || c.expect.anyOf.isEmpty()
                || c.expect.anyOf.stream().anyMatch(n -> n.equalsIgnoreCase(firstName));

        boolean argsValid = true;
        StringBuilder invalid = new StringBuilder();
        for (LlmToolCall tc : calls) {
            String why = argInvalidReason(tc);
            if (why != null) {
                argsValid = false;
                invalid.append(tc.name()).append("(").append(why).append(") ");
            }
        }

        Boolean argsMatch = null;
        if (c.expect != null && c.expect.args != null && c.expect.args.containsKey(firstName)) {
            argsMatch = argsMatchExpected(first, c.expect.args.get(firstName));
        }

        String detail = "called=" + names(calls)
                + (argsValid ? "" : " INVALID[" + invalid.toString().trim() + "]");
        return new Sample(firstName, selectionOk, argsValid, argsMatch, detail);
    }

    /** null if the call's args are valid; otherwise a short reason. */
    private static String argInvalidReason(LlmToolCall tc) {
        NumenTool tool = ToolRegistry.resolve(tc.name());
        if (tool == null) return "unknown tool";
        JsonObject args;
        try {
            String raw = tc.arguments();
            args = (raw == null || raw.isBlank()) ? new JsonObject()
                    : JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException ex) {
            return "args not JSON";
        }
        // Offline validation lives on the core adapter (not the MC-free contract):
        // it coerces the args, throwing on a missing/ill-typed one, without executing.
        if (!(tool instanceof NumenActionTool action)) {
            return null;   // non-adapter tool: no offline validator
        }
        try {
            action.checkArgs(args);
            return null;
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        } catch (RuntimeException ex) {
            return ex.getClass().getSimpleName();
        }
    }

    private static Boolean argsMatchExpected(LlmToolCall call, JsonObject expected) {
        JsonObject args;
        try {
            args = JsonParser.parseString(call.arguments()).getAsJsonObject();
        } catch (RuntimeException ex) {
            return false;
        }
        for (Map.Entry<String, JsonElement> e : expected.entrySet()) {
            if (!args.has(e.getKey())) return false;
            if (!looseMatch(args.get(e.getKey()), e.getValue())) return false;
        }
        return true;
    }

    /**
     * Acceptable-set value matching (BFCL-style), recursive:
     * <ul>
     *   <li>expected is an array → an acceptable-set: pass if the actual matches ANY element
     *       (e.g. {@code "structure": ["stronghold", "minecraft:stronghold"]});</li>
     *   <li>actual is an array → contains-match: pass if ANY element matches the expected
     *       (e.g. {@code block_ids} contains {@code iron_ore});</li>
     *   <li>scalars → numbers by value, strings case-insensitive & substring-tolerant, else equals.</li>
     * </ul>
     */
    private static boolean looseMatch(JsonElement actual, JsonElement expected) {
        if (actual == null || actual.isJsonNull()) return false;
        if (expected.isJsonArray()) {
            for (JsonElement e : expected.getAsJsonArray()) {
                if (looseMatch(actual, e)) return true;
            }
            return false;
        }
        if (actual.isJsonArray()) {
            for (JsonElement a : actual.getAsJsonArray()) {
                if (looseMatch(a, expected)) return true;
            }
            return false;
        }
        try {
            if (expected.getAsJsonPrimitive().isNumber()) {
                return actual.getAsDouble() == expected.getAsDouble();
            }
            if (expected.getAsJsonPrimitive().isString()) {
                String a = actual.getAsString().toLowerCase();
                String x = expected.getAsString().toLowerCase();
                return a.equals(x) || a.contains(x) || x.contains(a);
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return actual.equals(expected);
    }

    // ---- wire-format + context rendering (mirrors NumenLlmClient / buildEnvBlock / WorkBlockMemory) ----

    private static List<JsonObject> wireMessages(BenchCase c, LlmProvider provider) {
        List<JsonObject> wire = new ArrayList<>();
        if (c.prior != null) {
            for (RawMsg m : c.prior) {
                switch (m.role) {
                    case "user" -> wire.add(provider.buildUserMessage(m.content));
                    case "tool" -> wire.add(provider.buildToolResultMessage(m.tool_call_id, m.content));
                    case "assistant" -> {
                        List<LlmToolCall> calls = new ArrayList<>();
                        if (m.name != null && !m.name.isBlank()) {
                            calls.add(new LlmToolCall(
                                    m.tool_call_id == null ? "bench_call" : m.tool_call_id,
                                    m.name, m.arguments == null ? "{}" : m.arguments));
                        }
                        // Thinking models (e.g. deepseek-v4-flash) 400 if a historical assistant
                        // message omits reasoning_content. The live mod round-trips it via extras;
                        // mirror that — use the case's reasoning, or a minimal placeholder.
                        JsonObject extras = new JsonObject();
                        extras.addProperty("reasoning_content",
                                (m.reasoning != null && !m.reasoning.isBlank()) ? m.reasoning : "(prior step)");
                        wire.add(provider.assistantToRequestMessage(
                                new AssistantTurn(m.content == null ? "" : m.content, calls, extras)));
                    }
                    default -> { /* skip unknown roles */ }
                }
            }
        }
        // A first-turn case ends with the owner's request; a mid-trajectory case mined
        // from a real log ends at a tool result (request omitted) — the decision continues
        // from there.
        if (c.request != null && !c.request.isBlank()) {
            wire.add(provider.buildUserMessage(c.request));
        }
        return wire;
    }

    private static String renderEnv(EnvSpec env) {
        if (env == null) return null;
        return "<env>\n"
                + "  entity_uuid: bench-entity\n"
                + "  owner_name: " + (env.owner_name == null ? "Steve" : env.owner_name) + "\n"
                + "  dimension: " + (env.dimension == null ? "minecraft:overworld" : env.dimension) + "\n"
                + "  today: " + LocalDate.now() + "\n"
                + "</env>";
    }

    private static String renderKnownBlocks(List<KnownBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<known_blocks>\n");
        sb.append("  Functional blocks you have already placed or used. Walk back to these ")
          .append("instead of crafting/placing duplicates:\n");
        for (KnownBlock b : blocks) {
            sb.append("  <block type=\"").append(b.type)
              .append("\" x=\"").append(b.x)
              .append("\" y=\"").append(b.y)
              .append("\" z=\"").append(b.z).append("\"/>\n");
        }
        sb.append("</known_blocks>");
        return sb.toString();
    }

    /** Compose the chat-completions URL the same way NumenLlmClient does. */
    public static String composeUrl(Config cfg, LlmProvider provider) {
        String base = (cfg.baseUrl != null && !cfg.baseUrl.isBlank())
                ? cfg.baseUrl : provider.defaultBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    private static String names(List<LlmToolCall> calls) {
        List<String> ns = new ArrayList<>();
        for (LlmToolCall c : calls) ns.add(c.name());
        return String.join(",", ns);
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException ex) { return def; }
    }

    private static Double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (RuntimeException ex) { return null; }
    }
}
