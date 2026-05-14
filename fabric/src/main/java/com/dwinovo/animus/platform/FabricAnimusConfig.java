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

/**
 * Fabric implementation of {@link IAnimusConfig}. Reads a JSON file under
 * {@code <gameDir>/config/animus.json} on construction; if the file is
 * missing it's created with built-in defaults so users get a discoverable
 * template on first launch.
 *
 * <p>Fabric has no first-party config system the way NeoForge does, so we
 * keep the format intentionally simple — straight Gson reflection-mapped to
 * a public-fields POJO. Comments aren't supported by the JSON spec, so the
 * default file ships a {@code _readme} field explaining each entry. Users
 * who delete it lose the inline docs but the config still loads fine.
 */
public final class FabricAnimusConfig implements IAnimusConfig {

    private static final String FILE_NAME = "animus.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ConfigData data;

    /** Default constructor used by {@code ServiceLoader}. */
    public FabricAnimusConfig() {
        this.data = loadOrCreate();
    }

    private ConfigData loadOrCreate() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        if (Files.exists(configPath)) {
            try (Reader r = Files.newBufferedReader(configPath)) {
                ConfigData parsed = GSON.fromJson(r, ConfigData.class);
                if (parsed != null) return parsed.applyDefaults();
            } catch (IOException | JsonSyntaxException ex) {
                Constants.LOG.warn("[animus-config] failed to read {}: {}; falling back to defaults",
                        configPath, ex.getMessage());
            }
        }
        ConfigData defaults = ConfigData.defaults();
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer w = Files.newBufferedWriter(configPath)) {
                GSON.toJson(defaults, w);
            }
            Constants.LOG.info("[animus-config] wrote default config to {}", configPath);
        } catch (IOException ex) {
            Constants.LOG.warn("[animus-config] could not write default config to {}: {}",
                    configPath, ex.getMessage());
        }
        return defaults;
    }

    @Override
    public String getApiKey() { return data.apiKey == null ? "" : data.apiKey; }

    @Override
    public String getBaseUrl() { return data.baseUrl == null ? "" : data.baseUrl; }

    @Override
    public String getModel() { return data.model == null ? "" : data.model; }

    @Override
    public String getSystemPrompt() { return data.systemPrompt == null ? "" : data.systemPrompt; }

    /**
     * Gson-serialised POJO. Public fields kept simple on purpose — the JSON
     * file IS the schema, no need for an extra layer.
     */
    public static final class ConfigData {
        /** Free-form note for human readers; ignored by the loader. */
        public String _readme = "Animus mod configuration. Set api_key (required) and optionally base_url to point at any OpenAI-compatible endpoint. Restart the server / world for changes to take effect.";
        public String apiKey = "";
        public String baseUrl = "";
        public String model = "gpt-5-2-mini";
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
            if (systemPrompt == null) systemPrompt = d.systemPrompt;
            return this;
        }
    }
}
