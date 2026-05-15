package com.dwinovo.animus.platform;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.platform.services.IAnimusConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Fabric implementation of {@link IAnimusConfig}. Reads a JSON file at
 * {@code <gameDir>/config/animus.json} on construction; if the file is
 * missing it's created with built-in defaults so users get a discoverable
 * template on first launch.
 *
 * <p>Fabric has no first-party config system the way NeoForge does, so the
 * format is intentionally minimal: plain Gson reflection over a public-field
 * POJO, JSON spec only (no comments — the {@code _readme} field stands in).
 *
 * <h2>Non-destructive read</h2>
 * If the file exists but fails to parse, the user's content is preserved:
 * we copy the broken file to {@code animus.json.bak-<timestamp>}, leave the
 * original untouched on disk, and serve defaults from memory for this
 * session. The user can fix or restore their file without losing edits.
 * Earlier versions silently overwrote with defaults on parse failure — that
 * silently destroyed user API keys and is the canonical footgun this
 * implementation now avoids.
 */
public final class FabricAnimusConfig implements IAnimusConfig {

    private static final String FILE_NAME = "animus.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ConfigData data;

    /** Default constructor used by {@code ServiceLoader}. */
    public FabricAnimusConfig() {
        this.data = loadOrCreate();
        logLoadedState();
    }

    private ConfigData loadOrCreate() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        if (Files.exists(configPath)) {
            try (Reader r = Files.newBufferedReader(configPath)) {
                ConfigData parsed = GSON.fromJson(r, ConfigData.class);
                if (parsed != null) {
                    Constants.LOG.info("[animus-config] loaded {}", configPath);
                    return parsed.applyDefaults();
                }
                // GSON returned null — file was effectively empty. Back up
                // (since it might be a half-written edit) and let the
                // template be regenerated below.
                Constants.LOG.warn("[animus-config] {} parsed to null; backing up and rewriting",
                        configPath);
                backup(configPath);
            } catch (JsonSyntaxException ex) {
                // Bad JSON — could be a user typo. Preserve their file by
                // copying to a .bak, do NOT overwrite the original, return
                // in-memory defaults for this session.
                Constants.LOG.warn("[animus-config] failed to parse {}: {}; preserving original file as-is",
                        configPath, ex.getMessage());
                Path bak = backup(configPath);
                if (bak != null) {
                    Constants.LOG.warn("[animus-config] copy of broken file at {}", bak);
                }
                Constants.LOG.warn("[animus-config] serving DEFAULTS this session — fix {} and restart",
                        configPath);
                return ConfigData.defaults();
            } catch (IOException ex) {
                Constants.LOG.warn("[animus-config] could not read {}: {}; serving defaults this session",
                        configPath, ex.getMessage());
                return ConfigData.defaults();
            }
        }
        return writeDefaultTemplate(configPath);
    }

    private ConfigData writeDefaultTemplate(Path configPath) {
        ConfigData defaults = ConfigData.defaults();
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer w = Files.newBufferedWriter(configPath)) {
                GSON.toJson(defaults, w);
            }
            Constants.LOG.info("[animus-config] wrote default template to {} — set 'apiKey' and restart",
                    configPath);
        } catch (IOException ex) {
            Constants.LOG.warn("[animus-config] could not write default config to {}: {}",
                    configPath, ex.getMessage());
        }
        return defaults;
    }

    /**
     * Copy the file to {@code <name>.bak-<yyyyMMdd-HHmmss>} alongside it. Best
     * effort — returns {@code null} on failure and the caller carries on.
     * Used both for parse-failure backups (the original is left in place)
     * and null-parse cases (the original is then overwritten by the
     * template-write path).
     */
    private static Path backup(Path original) {
        try {
            String ts = LocalDateTime.now().format(BACKUP_TIMESTAMP);
            Path bak = original.resolveSibling(original.getFileName() + ".bak-" + ts);
            Files.copy(original, bak, StandardCopyOption.REPLACE_EXISTING);
            return bak;
        } catch (IOException ex) {
            Constants.LOG.warn("[animus-config] could not back up {}: {}", original, ex.getMessage());
            return null;
        }
    }

    /**
     * Log non-sensitive load state for debugging. Logs the API-key length
     * (not the key itself) so users can immediately see at startup whether
     * a key was picked up — the single most common config-debugging question.
     */
    private void logLoadedState() {
        String key = data.apiKey == null ? "" : data.apiKey;
        String model = data.model == null ? "" : data.model;
        String base = data.baseUrl == null ? "" : data.baseUrl;
        String prov = data.provider == null ? "" : data.provider;
        Constants.LOG.info("[animus-config] api_key length={}, provider={}, model={}, base_url={}",
                key.length(),
                prov.isEmpty() ? "<empty>" : prov,
                model.isEmpty() ? "<empty>" : model,
                base.isEmpty() ? "<default>" : base);
    }

    @Override
    public String getApiKey() { return data.apiKey == null ? "" : data.apiKey; }

    @Override
    public String getBaseUrl() { return data.baseUrl == null ? "" : data.baseUrl; }

    @Override
    public String getModel() { return data.model == null ? "" : data.model; }

    @Override
    public String getSystemPrompt() { return data.systemPrompt == null ? "" : data.systemPrompt; }

    @Override
    public String getProvider() { return data.provider == null ? "openai" : data.provider; }

    // ---- mutations ----

    @Override
    public void setApiKey(String value) { data.apiKey = value == null ? "" : value; }

    @Override
    public void setBaseUrl(String value) { data.baseUrl = value == null ? "" : value; }

    @Override
    public void setModel(String value) { data.model = value == null ? "" : value; }

    @Override
    public void setProvider(String value) { data.provider = value == null ? "openai" : value; }

    @Override
    public void setSystemPrompt(String value) { data.systemPrompt = value == null ? "" : value; }

    @Override
    public void save() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer w = Files.newBufferedWriter(configPath)) {
                GSON.toJson(data, w);
            }
            Constants.LOG.info("[animus-config] saved {} (api_key length={}, provider={}, model={})",
                    configPath,
                    data.apiKey == null ? 0 : data.apiKey.length(),
                    data.provider, data.model);
        } catch (IOException ex) {
            Constants.LOG.warn("[animus-config] save failed for {}: {}", configPath, ex.getMessage());
        }
    }

    /**
     * Gson-serialised POJO. Public fields kept simple on purpose — the JSON
     * file IS the schema, no need for an extra layer. The {@code _readme}
     * documents the actual JSON keys (camelCase, matching Gson's default
     * IDENTITY naming policy) so users editing the file can't accidentally
     * write {@code "api_key"} (snake_case) and have it silently ignored.
     */
    public static final class ConfigData {
        /** Free-form note for human readers; ignored by the loader. */
        public String _readme = "Animus mod configuration. Set 'apiKey' (required). 'provider' picks the wire-format adapter AND its default base URL: openai | deepseek | moonshot (alias kimi) | minimax | volcengine (alias doubao, ark) | dashscope (alias qwen, tongyi, aliyun). Set 'baseUrl' only if you need to override the provider default (e.g. self-hosted proxy or non-default region). 'model' is whatever the backend recognises. Field names are camelCase. Restart for changes to take effect.";
        public String apiKey = "";
        public String baseUrl = "";
        public String model = "gpt-5-2-mini";
        public String provider = "openai";
        public String systemPrompt = "You are the mind of a Minecraft creature named Animus. Use the provided tools to act in the world. Be concise. If a tool fails, decide whether to retry, try a different approach, or stop.";

        static ConfigData defaults() {
            return new ConfigData();
        }

        /** Fill in defaults for missing fields on a partially-populated file. */
        ConfigData applyDefaults() {
            ConfigData d = defaults();
            if (apiKey == null) apiKey = d.apiKey;
            if (baseUrl == null) baseUrl = d.baseUrl;
            if (model == null || model.isBlank()) model = d.model;
            if (provider == null || provider.isBlank()) provider = d.provider;
            if (systemPrompt == null) systemPrompt = d.systemPrompt;
            return this;
        }
    }
}
