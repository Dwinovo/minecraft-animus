package com.dwinovo.animus.data;

/**
 * Single source of truth for every translation key the mod emits. Both
 * loader-side data generators ({@code FabricModLanguageProvider},
 * {@code ModLanguageProvider}) feed their builder through this class, so
 * adding / renaming / translating a key happens in one Java file and
 * propagates to every locale's emitted JSON in one go.
 *
 * <h2>Why a class and not hand-edited JSON</h2>
 * <ul>
 *   <li>IDE rename refactors the key everywhere at once.</li>
 *   <li>Missing translations show up as compile errors, not silent
 *       missing-locale fallbacks.</li>
 *   <li>Key constants can be referenced from rendering / GUI code via
 *       {@link Keys} so the lookup path is type-checked.</li>
 *   <li>Future model-manifest-driven {@code display_name_key}s can be
 *       cross-checked against the {@link Keys} table at load time.</li>
 * </ul>
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

        /** Entity display name shown on name plates and spawn eggs. */
        public static final String ENTITY_ANIMUS = "entity.animus.animus";

        // Model-chooser GUI labels.
        public static final String GUI_CHOOSE_MODEL_TITLE     = "animus.gui.choose_model.title";
        public static final String GUI_CHOOSE_MODEL_REFRESH   = "animus.gui.choose_model.refresh";
        public static final String GUI_CHOOSE_MODEL_APPLY     = "animus.gui.choose_model.apply";
        public static final String GUI_CHOOSE_MODEL_CANCEL    = "animus.gui.choose_model.cancel";
        public static final String GUI_CHOOSE_MODEL_NO_MODELS = "animus.gui.choose_model.no_models";
        public static final String GUI_CHOOSE_MODEL_CURRENT   = "animus.gui.choose_model.current";
        public static final String GUI_CHOOSE_MODEL_AUTHOR    = "animus.gui.choose_model.author";
        public static final String GUI_CHOOSE_MODEL_REFRESH_DONE = "animus.gui.choose_model.refresh_done";
        public static final String GUI_CHOOSE_MODEL_TOO_FAR   = "animus.gui.choose_model.too_far";

        /** Namespace pretty-name keys, used as group headers in the chooser. */
        public static final String GUI_CHOOSE_MODEL_NAMESPACE_ANIMUS      = "animus.gui.choose_model.namespace.animus";
        public static final String GUI_CHOOSE_MODEL_NAMESPACE_ANIMUS_USER = "animus.gui.choose_model.namespace.animus_user";

        // Built-in model display strings.
        public static final String MODEL_HACHIWARE_NAME        = "animus.model.hachiware.name";
        public static final String MODEL_HACHIWARE_DESCRIPTION = "animus.model.hachiware.description";

        // Owner prompt GUI labels.
        public static final String GUI_PROMPT_TITLE  = "animus.gui.prompt.title";
        public static final String GUI_PROMPT_INPUT  = "animus.gui.prompt.input";
        public static final String GUI_PROMPT_HINT   = "animus.gui.prompt.hint";
        public static final String GUI_PROMPT_SEND   = "animus.gui.prompt.send";
        public static final String GUI_PROMPT_CANCEL = "animus.gui.prompt.cancel";
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
        adder.add(Keys.ENTITY_ANIMUS, "Animus");

        adder.add(Keys.GUI_CHOOSE_MODEL_TITLE,        "Choose Model");
        adder.add(Keys.GUI_CHOOSE_MODEL_REFRESH,      "Refresh");
        adder.add(Keys.GUI_CHOOSE_MODEL_APPLY,        "Apply");
        adder.add(Keys.GUI_CHOOSE_MODEL_CANCEL,       "Cancel");
        adder.add(Keys.GUI_CHOOSE_MODEL_NO_MODELS,    "No models available");
        adder.add(Keys.GUI_CHOOSE_MODEL_CURRENT,      "Current");
        adder.add(Keys.GUI_CHOOSE_MODEL_AUTHOR,       "Author: %s");
        adder.add(Keys.GUI_CHOOSE_MODEL_REFRESH_DONE, "Reloaded %d custom model(s)");
        adder.add(Keys.GUI_CHOOSE_MODEL_TOO_FAR,      "Too far from the entity");
        adder.add(Keys.GUI_CHOOSE_MODEL_NAMESPACE_ANIMUS,      "Built-in");
        adder.add(Keys.GUI_CHOOSE_MODEL_NAMESPACE_ANIMUS_USER, "Custom");

        adder.add(Keys.MODEL_HACHIWARE_NAME,        "Hachiware");
        adder.add(Keys.MODEL_HACHIWARE_DESCRIPTION, "Default model — a small black-and-white feline character.");

        adder.add(Keys.GUI_PROMPT_TITLE,  "Talk to Animus");
        adder.add(Keys.GUI_PROMPT_INPUT,  "Prompt");
        adder.add(Keys.GUI_PROMPT_HINT,   "Tell the entity what to do...");
        adder.add(Keys.GUI_PROMPT_SEND,   "Send");
        adder.add(Keys.GUI_PROMPT_CANCEL, "Cancel");
    }

    private static void addZh(Adder adder) {
        adder.add(Keys.ENTITY_ANIMUS, "Animus");

        adder.add(Keys.GUI_CHOOSE_MODEL_TITLE,        "选择模型");
        adder.add(Keys.GUI_CHOOSE_MODEL_REFRESH,      "刷新");
        adder.add(Keys.GUI_CHOOSE_MODEL_APPLY,        "应用");
        adder.add(Keys.GUI_CHOOSE_MODEL_CANCEL,       "取消");
        adder.add(Keys.GUI_CHOOSE_MODEL_NO_MODELS,    "暂无可用模型");
        adder.add(Keys.GUI_CHOOSE_MODEL_CURRENT,      "当前");
        adder.add(Keys.GUI_CHOOSE_MODEL_AUTHOR,       "作者：%s");
        adder.add(Keys.GUI_CHOOSE_MODEL_REFRESH_DONE, "已重新加载 %d 个自定义模型");
        adder.add(Keys.GUI_CHOOSE_MODEL_TOO_FAR,      "距离实体太远");
        adder.add(Keys.GUI_CHOOSE_MODEL_NAMESPACE_ANIMUS,      "内置");
        adder.add(Keys.GUI_CHOOSE_MODEL_NAMESPACE_ANIMUS_USER, "玩家自定义");

        adder.add(Keys.MODEL_HACHIWARE_NAME,        "帕奇猫");
        adder.add(Keys.MODEL_HACHIWARE_DESCRIPTION, "默认模型——一只黑白花纹的小猫角色。");

        adder.add(Keys.GUI_PROMPT_TITLE,  "对话");
        adder.add(Keys.GUI_PROMPT_INPUT,  "输入");
        adder.add(Keys.GUI_PROMPT_HINT,   "告诉它你想让它做什么……");
        adder.add(Keys.GUI_PROMPT_SEND,   "发送");
        adder.add(Keys.GUI_PROMPT_CANCEL, "取消");
    }
}
