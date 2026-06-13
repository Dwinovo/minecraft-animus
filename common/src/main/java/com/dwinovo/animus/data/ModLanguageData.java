package com.dwinovo.animus.data;

/**
 * Single source of truth for every translation key the mod emits. Both
 * loader-side data generators ({@code FabricModLanguageProvider},
 * {@code ModLanguageProvider}) feed their builder through this class, so
 * adding / renaming / translating a key happens in one Java file and
 * propagates to every locale's emitted JSON in one go.
 *
 * <h2>Adding a new key</h2>
 * <ol>
 *   <li>Add a constant under {@link Keys}.</li>
 *   <li>Add an {@code adder.add(Keys.MY_KEY, "...")} call inside
 *       {@link #addEn} and {@link #addZh}.</li>
 *   <li>Re-run {@code ./gradlew :fabric:runDatagen :neoforge:runData}.</li>
 * </ol>
 */
public final class ModLanguageData {

    private ModLanguageData() {}

    /** Sink for the loader-specific data generator's translation builder. */
    @FunctionalInterface
    public interface Adder {
        void add(String key, String value);
    }

    /** Catalogue of every key this mod emits. Reference these from runtime code. */
    public static final class Keys {

        private Keys() {}

        // Settings GUI labels.
        public static final String GUI_SETTINGS_TITLE       = "animus.gui.settings.title";
        public static final String GUI_SETTINGS_PROVIDER    = "animus.gui.settings.provider";
        public static final String GUI_SETTINGS_API_KEY     = "animus.gui.settings.api_key";
        public static final String GUI_SETTINGS_MODEL       = "animus.gui.settings.model";
        public static final String GUI_SETTINGS_BASE_URL    = "animus.gui.settings.base_url";
        public static final String GUI_SETTINGS_BASE_URL_HINT = "animus.gui.settings.base_url_hint";
        public static final String GUI_SETTINGS_SAVE        = "animus.gui.settings.save";
        public static final String GUI_SETTINGS_CANCEL      = "animus.gui.settings.cancel";
        public static final String GUI_SETTINGS_SAVED       = "animus.gui.settings.saved";

        /** Hotkey: open the companion roster panel (shown in Controls settings). */
        public static final String KEY_OPEN_ROSTER = "key.animus.open_roster";
    }

    /** Loader-side providers funnel both English and Simplified Chinese through here. */
    public static void addTranslations(String locale, Adder adder) {
        if ("zh_cn".equals(locale)) {
            addZh(adder);
        } else {
            addEn(adder);
        }
    }

    private static void addEn(Adder adder) {
        adder.add(Keys.GUI_SETTINGS_TITLE,         "Animus Settings");
        adder.add(Keys.GUI_SETTINGS_PROVIDER,      "Provider");
        adder.add(Keys.GUI_SETTINGS_API_KEY,       "API Key");
        adder.add(Keys.GUI_SETTINGS_MODEL,         "Model");
        adder.add(Keys.GUI_SETTINGS_BASE_URL,      "Base URL (optional)");
        adder.add(Keys.GUI_SETTINGS_BASE_URL_HINT, "Leave empty to use provider default");
        adder.add(Keys.GUI_SETTINGS_SAVE,          "Save");
        adder.add(Keys.GUI_SETTINGS_CANCEL,        "Cancel");
        adder.add(Keys.GUI_SETTINGS_SAVED,         "Saved");

        adder.add(Keys.KEY_OPEN_ROSTER, "Open Companion Roster");
    }

    private static void addZh(Adder adder) {
        adder.add(Keys.GUI_SETTINGS_TITLE,         "Animus 设置");
        adder.add(Keys.GUI_SETTINGS_PROVIDER,      "服务商");
        adder.add(Keys.GUI_SETTINGS_API_KEY,       "API Key");
        adder.add(Keys.GUI_SETTINGS_MODEL,         "模型");
        adder.add(Keys.GUI_SETTINGS_BASE_URL,      "Base URL（可选）");
        adder.add(Keys.GUI_SETTINGS_BASE_URL_HINT, "留空使用服务商默认地址");
        adder.add(Keys.GUI_SETTINGS_SAVE,          "保存");
        adder.add(Keys.GUI_SETTINGS_CANCEL,        "取消");
        adder.add(Keys.GUI_SETTINGS_SAVED,         "已保存");

        adder.add(Keys.KEY_OPEN_ROSTER, "打开伙伴名册");
    }
}
