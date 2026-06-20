package com.dwinovo.tulpa.platform;

import com.dwinovo.tulpa.platform.services.ITulpaConfig;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Forge implementation of {@link ITulpaConfig}. Backed by a
 * {@link ForgeConfigSpec} that Forge serialises as TOML under
 * {@code <gameDir>/config/tulpa-common.toml}. Editable in-game via Forge's
 * built-in config screen (Mods → Tulpa → Config).
 *
 * <h2>Registration timing</h2>
 * The static {@link #SPEC} is constructed during class load (just data — no
 * I/O), so it's safe to reference from the Mod constructor. {@code TulpaMod}
 * registers the spec via {@code ModLoadingContext.registerConfig(...)}; Forge
 * loads / writes the TOML when the world loads.
 *
 * <p>Calling {@link ForgeConfigSpec.ConfigValue#get()} before the spec has been
 * loaded returns the declared default, so reads from this class are safe at any
 * point after {@code SPEC} is registered.
 *
 * <p>This is a straight port of the NeoForge {@code ModConfigSpec} variant —
 * Forge's {@code ForgeConfigSpec} exposes the identical builder API, only the
 * class name differs.
 */
public final class ForgeTulpaConfig implements ITulpaConfig {

    public static final ForgeConfigSpec.ConfigValue<String> API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> BASE_URL;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL;
    public static final ForgeConfigSpec.ConfigValue<String> PROVIDER;
    public static final ForgeConfigSpec.ConfigValue<String> PROXY;
    public static final ForgeConfigSpec.ConfigValue<String> SYSTEM_PROMPT;
    public static final ForgeConfigSpec SPEC;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
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
        PROXY = b.comment("Optional HTTP proxy for LLM calls as host:port (empty = direct).")
                .define("proxy", "");

        b.pop();
        b.comment("Behaviour tuning.").push("agent");
        // Deliberately short. The planning behaviour (use todowrite for
        // multi-step tasks, load_skill to fetch detailed workflows) emerges
        // entirely from those tools' own descriptions plus the runtime-injected
        // <available_skills> XML block — adding rules here just dilutes
        // attention. Mirrors opencode's default.txt minimalist style.
        SYSTEM_PROMPT = b.comment("System prompt prepended to every conversation.")
                .define("system_prompt",
                        "You are Tulpa, a Minecraft entity controlled by the player who owns you.\n"
                                + "Use the tools provided to act in the world; output text only to talk to your owner.");
        b.pop();

        SPEC = b.build();
    }

    /** Default constructor used by {@code ServiceLoader}. */
    public ForgeTulpaConfig() {}

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

    @Override
    public String getProxy() {
        return safe(PROXY);
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
    public void setProxy(String value) {
        PROXY.set(value == null ? "" : value);
    }

    @Override
    public void setSystemPrompt(String value) {
        SYSTEM_PROMPT.set(value == null ? "" : value);
    }

    /**
     * Flush the in-memory config to disk. The setters above only mutate the
     * loaded NightConfig in memory — {@link ForgeConfigSpec.ConfigValue#set}'s
     * own javadoc states it does NOT write to disk; call
     * {@link ForgeConfigSpec#save()} eventually. Without this explicit save an
     * in-game settings change is lost on shutdown (the values revert to
     * whatever was last on disk), which is exactly the "reverts to default"
     * bug players hit after changing provider / key in the GUI.
     *
     * <p>Guarded by {@link ForgeConfigSpec#isLoaded()}: {@code save()} throws if
     * the spec hasn't been bound to a config file yet (e.g. called absurdly
     * early). In that case the mutations live in the cached values and the
     * normal Forge load/correct cycle will persist them.
     */
    @Override
    public void save() {
        if (SPEC.isLoaded()) {
            SPEC.save();
        }
        com.dwinovo.tulpa.Constants.LOG.info(
                "[tulpa-config] saved (api_key length={}, provider={}, model={})",
                safe(API_KEY).length(), safe(PROVIDER), safe(MODEL));
    }

    /**
     * Guard against the (rare) corner case where a config value is read before
     * the spec is fully bound — returns the empty string instead of letting an
     * exception escape into the LLM call site.
     */
    private static String safe(ForgeConfigSpec.ConfigValue<String> v) {
        try {
            String s = v.get();
            return s == null ? "" : s;
        } catch (IllegalStateException ex) {
            return "";
        }
    }
}
