package com.dwinovo.animus.platform.services;

/**
 * Cross-loader read surface for user-editable mod configuration. Each loader
 * ships its own implementation hooked into its native config system —
 * NeoForge wraps a {@code ModConfigSpec} (TOML), Fabric reads a Gson JSON
 * file under {@code <gameDir>/config/animus.json}.
 *
 * <h2>Read freshness</h2>
 * Values are read on every call. NeoForge's {@code ConfigValue.get()} is
 * already lazy + cached at the framework level; Fabric's JSON impl caches
 * the parsed values in memory and only re-reads on explicit
 * {@code /reload}-style triggers (Phase-2). For MVP, edits to the config
 * file require a server restart to take effect — acceptable.
 *
 * <h2>Why no setters</h2>
 * Mutation paths differ wildly between loaders (NeoForge needs the screen
 * config GUI, Fabric needs an external editor or our own command). Keep
 * this interface read-only; a future {@code IAnimusConfigEditor} can layer
 * on top when we need it.
 */
public interface IAnimusConfig {

    /**
     * OpenAI / OpenAI-compatible API key. Empty string if unset — the LLM
     * client refuses to start a chat completion in that case and logs once.
     */
    String getApiKey();

    /**
     * Optional base URL override. Empty string means "use the SDK default"
     * ({@code https://api.openai.com/v1}). Set to e.g.
     * {@code https://api.deepseek.com/v1} to point at a compatible backend.
     */
    String getBaseUrl();

    /**
     * Model id passed to the {@code model} field of every chat completion
     * request. Backend-defined string ({@code gpt-5-2-mini},
     * {@code claude-3-5-sonnet} via proxy, {@code deepseek-chat}, etc.).
     */
    String getModel();

    /**
     * System prompt prepended to every conversation. Empty string for none.
     * The agent layer adds tool-use guidance automatically on top of this.
     */
    String getSystemPrompt();
}
