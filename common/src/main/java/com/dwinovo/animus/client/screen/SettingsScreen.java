package com.dwinovo.animus.client.screen;

import com.dwinovo.animus.agent.llm.AnimusLlmClient;
import com.dwinovo.animus.data.ModLanguageData;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.platform.services.IAnimusConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * In-game settings GUI. Lets the owner change provider / API key / model /
 * base URL without restarting the game. Reached from {@link PromptScreen}'s
 * Settings button.
 *
 * <h2>Save flow</h2>
 * <ol>
 *   <li>Click Save → mutate {@link Services#CONFIG} via the new setters</li>
 *   <li>{@code config.save()} flushes to disk (Fabric: write JSON;
 *       NeoForge: relies on ModConfigSpec auto-persist)</li>
 *   <li>{@link AnimusLlmClient#reset()} invalidates the cached singleton</li>
 *   <li>Return to the parent screen (the PromptScreen we came from)</li>
 *   <li>Next prompt → {@code AnimusLlmClient.instance()} rebuilds with the
 *       updated config (new provider / URL / key / model)</li>
 * </ol>
 *
 * <p>Per-entity {@code ConvoState} is preserved across the swap, so an
 * in-progress chat continues with the new backend on the very next turn.
 * Most backends are OpenAI-protocol-compatible at message-shape level, so
 * cross-provider conversation history survives without translation. Users
 * who switch between very different model families (e.g. thinking on/off)
 * may want to wipe the convo manually — Phase-2 reset button.
 *
 * <h2>Provider picker</h2>
 * Six providers shown as a 2×3 grid of buttons; the currently-selected one
 * is inactive (visually distinct) just like the model chooser pattern in
 * {@link ChooseModelScreen}. Selecting a provider updates the placeholder
 * hints on the model and base-URL fields to that provider's canonical
 * values, so users always see what the API expects.
 *
 * <h2>API key is plain text</h2>
 * No masking on the EditBox — this is a single-player / personal-client
 * scenario, not a shared screen. Future polish: toggle to mask.
 */
public final class SettingsScreen extends Screen {

    /** Display rows on the provider grid. Order is by relevance to Chinese users (most likely target audience). */
    private static final List<ProviderOption> PROVIDERS = List.of(
            new ProviderOption("openai",     "OpenAI",   "gpt-5-2-mini",
                    "https://api.openai.com/v1"),
            new ProviderOption("deepseek",   "DeepSeek", "deepseek-v4-pro",
                    "https://api.deepseek.com/beta"),
            new ProviderOption("moonshot",   "Kimi",     "kimi-k2.5-preview",
                    "https://api.moonshot.ai/v1"),
            new ProviderOption("minimax",    "MiniMax",  "MiniMax-M2",
                    "https://api.minimax.io/v1"),
            new ProviderOption("volcengine", "Doubao",   "doubao-1-6-pro-256k-250115",
                    "https://ark.cn-beijing.volces.com/api/v3"),
            new ProviderOption("dashscope",  "Qwen",     "qwen3-max",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1"));

    private static final int CONTENT_WIDTH = 300;
    private static final int CONTENT_HEIGHT = 230;
    private static final int LABEL_HEIGHT   = 12;
    private static final int INPUT_HEIGHT   = 20;
    private static final int BUTTON_HEIGHT  = 20;
    private static final int BUTTON_GAP     = 4;
    private static final int FIELD_GAP      = 22;
    private static final int PROVIDER_ROWS = 2;
    private static final int PROVIDER_COLS = 3;

    private final @Nullable Screen parent;

    private String currentProvider;
    private EditBox apiKeyInput;
    private EditBox modelInput;
    private EditBox baseUrlInput;

    public SettingsScreen(@Nullable Screen parent) {
        super(Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_TITLE));
        this.parent = parent;
        IAnimusConfig cfg = Services.CONFIG;
        this.currentProvider = normalize(cfg.getProvider());
    }

    /** Convenience entry point (e.g. for a future keybind). */
    public static void open(@Nullable Screen parent) {
        Minecraft.getInstance().setScreen(new SettingsScreen(parent));
    }

    @Override
    protected void init() {
        clearWidgets();

        int left = (this.width - CONTENT_WIDTH) / 2;
        int top  = (this.height - CONTENT_HEIGHT) / 2;
        int y = top + 12;

        // -- Provider picker (label + grid)
        y += LABEL_HEIGHT;
        int cellWidth = (CONTENT_WIDTH - BUTTON_GAP * (PROVIDER_COLS - 1)) / PROVIDER_COLS;
        for (int i = 0; i < PROVIDERS.size(); i++) {
            ProviderOption opt = PROVIDERS.get(i);
            int col = i % PROVIDER_COLS;
            int row = i / PROVIDER_COLS;
            int bx = left + col * (cellWidth + BUTTON_GAP);
            int by = y + row * (BUTTON_HEIGHT + BUTTON_GAP);
            SimpleButton btn = new SimpleButton(bx, by, cellWidth, BUTTON_HEIGHT,
                    Component.literal(opt.displayName()),
                    b -> selectProvider(opt.id()));
            // The currently-selected provider's button is rendered inactive.
            if (opt.id().equals(currentProvider)) {
                btn.active = false;
            }
            addRenderableWidget(btn);
        }
        y += PROVIDER_ROWS * (BUTTON_HEIGHT + BUTTON_GAP) + 6;

        // -- API Key (label + input)
        y += LABEL_HEIGHT;
        this.apiKeyInput = new EditBox(this.font, left, y, CONTENT_WIDTH, INPUT_HEIGHT,
                Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_API_KEY));
        this.apiKeyInput.setMaxLength(512);
        this.apiKeyInput.setValue(Services.CONFIG.getApiKey());
        addRenderableWidget(this.apiKeyInput);
        y += FIELD_GAP;

        // -- Model (label + input)
        y += LABEL_HEIGHT;
        ProviderOption activeOpt = activeOption();
        this.modelInput = new EditBox(this.font, left, y, CONTENT_WIDTH, INPUT_HEIGHT,
                Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_MODEL));
        this.modelInput.setMaxLength(128);
        this.modelInput.setValue(Services.CONFIG.getModel());
        this.modelInput.setHint(Component.literal(activeOpt.defaultModel()));
        addRenderableWidget(this.modelInput);
        y += FIELD_GAP;

        // -- Base URL (label + input with provider-default hint)
        y += LABEL_HEIGHT;
        this.baseUrlInput = new EditBox(this.font, left, y, CONTENT_WIDTH, INPUT_HEIGHT,
                Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_BASE_URL));
        this.baseUrlInput.setMaxLength(256);
        this.baseUrlInput.setValue(Services.CONFIG.getBaseUrl());
        this.baseUrlInput.setHint(Component.literal(activeOpt.defaultBaseUrl()));
        addRenderableWidget(this.baseUrlInput);
        y += FIELD_GAP;

        // -- Footer: [Cancel] [Save]
        int footerY = top + CONTENT_HEIGHT - BUTTON_HEIGHT;
        int btnW = 60;
        int rightX = left + CONTENT_WIDTH - btnW;
        int leftX  = rightX - btnW - BUTTON_GAP;
        addRenderableWidget(new SimpleButton(leftX, footerY, btnW, BUTTON_HEIGHT,
                Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_CANCEL),
                b -> this.onClose()));
        addRenderableWidget(new SimpleButton(rightX, footerY, btnW, BUTTON_HEIGHT,
                Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_SAVE),
                this::onSave));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int left = (this.width - CONTENT_WIDTH) / 2;
        int top  = (this.height - CONTENT_HEIGHT) / 2;

        graphics.centeredText(this.font, this.getTitle(), this.width / 2, top - 14, 0xFFFFFFFF);

        // Section labels. Y positions mirror the layout walk in init().
        int y = top + 12;
        graphics.text(this.font, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_PROVIDER),
                left, y, 0xFFAAAAAA);
        y += LABEL_HEIGHT;
        y += PROVIDER_ROWS * (BUTTON_HEIGHT + BUTTON_GAP) + 6;

        graphics.text(this.font, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_API_KEY),
                left, y, 0xFFAAAAAA);
        y += LABEL_HEIGHT;
        y += FIELD_GAP;

        graphics.text(this.font, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_MODEL),
                left, y, 0xFFAAAAAA);
        y += LABEL_HEIGHT;
        y += FIELD_GAP;

        graphics.text(this.font, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_BASE_URL),
                left, y, 0xFFAAAAAA);
    }

    private void selectProvider(String id) {
        this.currentProvider = id;
        // Preserve typed-in values across the rebuild — only the hints change.
        rebuildWithPreservedInputs();
    }

    private void rebuildWithPreservedInputs() {
        String savedKey   = apiKeyInput == null  ? "" : apiKeyInput.getValue();
        String savedModel = modelInput == null   ? "" : modelInput.getValue();
        String savedBase  = baseUrlInput == null ? "" : baseUrlInput.getValue();
        init();
        if (apiKeyInput != null)  apiKeyInput.setValue(savedKey);
        if (modelInput != null)   modelInput.setValue(savedModel);
        if (baseUrlInput != null) baseUrlInput.setValue(savedBase);
    }

    private void onSave(Button btn) {
        IAnimusConfig cfg = Services.CONFIG;
        cfg.setProvider(currentProvider);
        cfg.setApiKey(apiKeyInput.getValue());
        cfg.setModel(modelInput.getValue());
        cfg.setBaseUrl(baseUrlInput.getValue());
        cfg.save();
        AnimusLlmClient.reset();
        this.onClose();
    }

    @Override
    public void onClose() {
        if (parent != null) {
            this.minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private ProviderOption activeOption() {
        for (ProviderOption opt : PROVIDERS) {
            if (opt.id().equals(currentProvider)) return opt;
        }
        return PROVIDERS.get(0);
    }

    /** Map config aliases (kimi/doubao/qwen/...) onto canonical provider ids. */
    private static String normalize(String raw) {
        if (raw == null) return "openai";
        return switch (raw.toLowerCase()) {
            case "kimi" -> "moonshot";
            case "doubao", "ark" -> "volcengine";
            case "qwen", "tongyi", "aliyun" -> "dashscope";
            default -> raw.toLowerCase();
        };
    }

    /**
     * Picker entry. Display name is what shows on the button; default model
     * and base URL drive the placeholder hints on the input fields. Kept
     * inline as a record because the list is tiny and won't grow often.
     */
    private record ProviderOption(String id, String displayName,
                                   String defaultModel, String defaultBaseUrl) {}
}
