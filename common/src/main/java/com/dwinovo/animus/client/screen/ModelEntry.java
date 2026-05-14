package com.dwinovo.animus.client.screen;

import com.dwinovo.animus.anim.api.ModelManifestLibrary;
import com.dwinovo.animus.anim.baked.BakedModelManifest;
import com.dwinovo.animus.data.ModLanguageData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Display row for the model-chooser GUI list. Resolves the model
 * identifier into a human-readable name, an optional description, and a
 * namespace label (Built-in / Custom) so the GUI doesn't repeat the lookup
 * logic per render.
 *
 * <h2>Display name resolution</h2>
 * <ol>
 *   <li>{@link BakedModelManifest#displayNameKey()} — translatable component
 *       (preferred for mod built-ins)</li>
 *   <li>{@link BakedModelManifest#displayName()} — literal string (player
 *       packs that don't ship a lang file)</li>
 *   <li>The model id's path segment, in its raw form
 *       (e.g. {@code my_skin})</li>
 * </ol>
 */
public record ModelEntry(Identifier id, Component name, Component namespaceLabel,
                         Component description, @Nullable String author) {

    public static ModelEntry of(Identifier id) {
        BakedModelManifest manifest = ModelManifestLibrary.get(id);
        Component name = resolveName(id, manifest);
        Component namespaceLabel = resolveNamespaceLabel(id);
        Component description = resolveDescription(manifest);
        String author = manifest == null ? null : manifest.author();
        return new ModelEntry(id, name, namespaceLabel, description, author);
    }

    private static Component resolveName(Identifier id, BakedModelManifest manifest) {
        if (manifest != null) {
            if (manifest.displayNameKey() != null) {
                return Component.translatable(manifest.displayNameKey());
            }
            if (manifest.displayName() != null) {
                return Component.literal(manifest.displayName());
            }
        }
        return Component.literal(id.getPath());
    }

    private static Component resolveNamespaceLabel(Identifier id) {
        return switch (id.getNamespace()) {
            case "animus" -> Component.translatable(
                    ModLanguageData.Keys.GUI_CHOOSE_MODEL_NAMESPACE_ANIMUS);
            case "animus_user" -> Component.translatable(
                    ModLanguageData.Keys.GUI_CHOOSE_MODEL_NAMESPACE_ANIMUS_USER);
            default -> Component.literal(id.getNamespace());
        };
    }

    private static Component resolveDescription(BakedModelManifest manifest) {
        if (manifest == null) return Component.empty();
        if (manifest.descriptionKey() != null) {
            return Component.translatable(manifest.descriptionKey());
        }
        if (manifest.description() != null) {
            return Component.literal(manifest.description());
        }
        return Component.empty();
    }
}
