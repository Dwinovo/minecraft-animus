package com.dwinovo.animus.platform;

import com.dwinovo.animus.platform.services.IAnimusConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge implementation of {@link IAnimusConfig}. Backed by a
 * {@code ModConfigSpec} that NeoForge serialises as TOML under
 * {@code <gameDir>/config/animus-common.toml}. Editable in-game via
 * NeoForge's built-in config screen (Mods → Animus → Config).
 *
 * <h2>Registration timing</h2>
 * The static {@link #SPEC} is constructed during class load (just data —
 * no I/O), so it's safe to reference from the Mod constructor. The Mod
 * registers the spec via {@code ModContainer.registerConfig(...)}; NeoForge
 * loads / writes the TOML when the world loads.
 *
 * <p>Calling {@link ModConfigSpec.ConfigValue#get()} before the spec has
 * been loaded returns the declared default, so reads from this class are
 * safe at any point after {@code SPEC} is registered.
 */
public final class NeoForgeAnimusConfig implements IAnimusConfig {

    public static final ModConfigSpec.ConfigValue<String> API_KEY;
    public static final ModConfigSpec.ConfigValue<String> BASE_URL;
    public static final ModConfigSpec.ConfigValue<String> MODEL;
    public static final ModConfigSpec.ConfigValue<String> PROVIDER;
    public static final ModConfigSpec.ConfigValue<String> SYSTEM_PROMPT;
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("OpenAI / OpenAI-compatible API settings.").push("openai");

        API_KEY = b.comment("API key (sk-...) sent as the bearer token. Required.")
                .define("api_key", "");
        BASE_URL = b.comment("Base URL override. Leave empty for OpenAI's default endpoint.",
                "Examples: https://api.deepseek.com, http://localhost:11434 (Ollama)")
                .define("base_url", "");
        MODEL = b.comment("Model id sent as the `model` field. Backend-defined.")
                .define("model", "gpt-5-2-mini");
        PROVIDER = b.comment("Wire-format adapter AND default base URL selector.",
                "openai      → api.openai.com/v1 (default; also generic OpenAI-compat fallback)",
                "deepseek    → api.deepseek.com/beta (preserves reasoning_content for V4 thinking)",
                "moonshot    → api.moonshot.ai/v1 (Kimi; fill_reasoning_content safety net)",
                "  (alias: kimi)",
                "minimax     → api.minimax.io/v1",
                "volcengine  → ark.cn-beijing.volces.com/api/v3 (Doubao)",
                "  (aliases: doubao, ark)",
                "dashscope   → dashscope.aliyuncs.com/compatible-mode/v1 (Alibaba Qwen / Tongyi)",
                "  (aliases: qwen, tongyi, aliyun)",
                "Override 'base_url' below to use a different host (proxy / region split).")
                .define("provider", "openai");

        b.pop();
        b.comment("Behaviour tuning.").push("agent");
        // Deliberately short. The planning behaviour (use todowrite for
        // multi-step tasks, load_skill to fetch detailed workflows) emerges
        // entirely from those tools' own descriptions plus the runtime-injected
        // <available_skills> XML block — adding rules here just dilutes
        // attention. Mirrors opencode's default.txt minimalist style.
        SYSTEM_PROMPT = b.comment("System prompt prepended to every conversation.")
                .define("system_prompt",
                        "You are Animus, a Minecraft entity controlled by the player who owns you.\n"
                                + "Use the tools provided to act in the world; output text only to talk to your owner.");
        b.pop();

        SPEC = b.build();
    }

    /** Default constructor used by {@code ServiceLoader}. */
    public NeoForgeAnimusConfig() {}

    @Override
    public String getApiKey() {
        return safe(API_KEY);
    }

    @Override
    public String getBaseUrl() {
        return safe(BASE_URL);
    }

    @Override
    public String getModel() {
        return safe(MODEL);
    }

    @Override
    public String getSystemPrompt() {
        return safe(SYSTEM_PROMPT);
    }

    @Override
    public String getProvider() {
        String s = safe(PROVIDER);
        return s.isEmpty() ? "openai" : s;
    }

    // ---- mutations ----

    @Override
    public void setApiKey(String value) {
        API_KEY.set(value == null ? "" : value);
    }

    @Override
    public void setBaseUrl(String value) {
        BASE_URL.set(value == null ? "" : value);
    }

    @Override
    public void setModel(String value) {
        MODEL.set(value == null ? "" : value);
    }

    @Override
    public void setProvider(String value) {
        PROVIDER.set(value == null ? "openai" : value);
    }

    @Override
    public void setSystemPrompt(String value) {
        SYSTEM_PROMPT.set(value == null ? "" : value);
    }

    /**
     * NeoForge's ModConfigSpec auto-persists in-memory mutations on a
     * background schedule (world save, mod unload, etc.) — there's no
     * public {@code save()} on the spec itself. The {@code ConfigValue.set}
     * calls in the setters above are already enough for the values to
     * survive a normal shutdown. This method emits an INFO log line so
     * users see confirmation in the launcher log.
     */
    @Override
    public void save() {
        com.dwinovo.animus.Constants.LOG.info(
                "[animus-config] updated (api_key length={}, provider={}, model={}); "
                + "NeoForge will persist on next save tick",
                safe(API_KEY).length(), safe(PROVIDER), safe(MODEL));
    }

    /**
     * Guard against the (rare) corner case where a config value is read
     * before the spec is fully bound — returns the empty string instead of
     * letting an exception escape into the LLM call site.
     */
    private static String safe(ModConfigSpec.ConfigValue<String> v) {
        try {
            String s = v.get();
            return s == null ? "" : s;
        } catch (IllegalStateException ex) {
            return "";
        }
    }
}
