package com.dwinovo.animus.client.screen;

import java.util.List;

/**
 * The LLM providers offered in the settings UI — display name + the canonical
 * model / base-URL each expects (shown as field hints). Shared by the standalone
 * {@link SettingsScreen} and the {@link AnimusScreen} Settings tab.
 */
public final class LlmProviders {

    /** Order roughly by relevance to the target audience. */
    public static final List<Option> ALL = List.of(
            new Option("openai",     "OpenAI",   "gpt-5-2-mini",                "https://api.openai.com/v1"),
            new Option("deepseek",   "DeepSeek", "deepseek-v4-pro",             "https://api.deepseek.com/beta"),
            new Option("moonshot",   "Kimi",     "kimi-k2.5-preview",           "https://api.moonshot.ai/v1"),
            new Option("minimax",    "MiniMax",  "MiniMax-M2",                  "https://api.minimax.io/v1"),
            new Option("volcengine", "Doubao",   "doubao-1-6-pro-256k-250115",  "https://ark.cn-beijing.volces.com/api/v3"),
            new Option("dashscope",  "Qwen",     "qwen3-max",                   "https://dashscope.aliyuncs.com/compatible-mode/v1"));

    private LlmProviders() {}

    public record Option(String id, String displayName, String defaultModel, String defaultBaseUrl) {}

    public static Option byId(String id) {
        String norm = normalize(id);
        for (Option o : ALL) {
            if (o.id().equals(norm)) return o;
        }
        return ALL.get(0);
    }

    /** Map config aliases (kimi/doubao/qwen/…) onto canonical provider ids. */
    public static String normalize(String raw) {
        if (raw == null) return "openai";
        return switch (raw.toLowerCase()) {
            case "kimi" -> "moonshot";
            case "doubao", "ark" -> "volcengine";
            case "qwen", "tongyi", "aliyun" -> "dashscope";
            default -> raw.toLowerCase();
        };
    }
}
