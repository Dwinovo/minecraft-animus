package com.dwinovo.animus.anim.compile;

import com.dwinovo.animus.Constants;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Loads PNG textures from {@code <gameDir>/config/animus/models/textures/entities/}
 * and registers them with Minecraft's {@code TextureManager} under the
 * {@code animus_user:textures/entities/<id>} identifier. After registration the
 * renderer can bind these textures the same way it binds vanilla pack textures,
 * since {@code TextureManager.bind} resolves both manager-registered dynamic
 * textures and ResourceManager-loaded ones through the same identifier table.
 *
 * <p>This is the client-side companion of {@link ConfigModelLoader}. Bedrock
 * model files in the config tree reference their texture path through the
 * geometry/animation pair, but the texture sheet itself lives outside the
 * resource pack so this loader handles it explicitly.
 *
 * <p>Re-uploading on every reload: each scan walks the config dir and registers
 * every texture, overwriting prior entries. Minecraft's {@code TextureManager}
 * handles the prior entry's release internally. Releasing manually after a
 * delete would be cleaner, but the leaked memory is bounded by the number of
 * config textures (usually a handful) and a full mod reload reclaims it.
 */
public final class ConfigTextureLoader {

    public static final String TEXTURE_PATH_PREFIX = "textures/entities";
    public static final String PNG_EXTENSION = ".png";

    private ConfigTextureLoader() {}

    public static void scan(Path configDir) {
        if (configDir == null) return;
        Path texDir = configDir.resolve(TEXTURE_PATH_PREFIX);
        if (!Files.isDirectory(texDir)) return;

        try (Stream<Path> walk = Files.walk(texDir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(PNG_EXTENSION))
                .filter(Files::isRegularFile)
                .forEach(p -> register(texDir, p));
        } catch (IOException ex) {
            Constants.LOG.error("[animus-anim] failed to walk config textures dir {}: {}", texDir, ex.toString());
        }
    }

    private static void register(Path texRoot, Path pngFile) {
        String shortName = texRoot.relativize(pngFile).toString().replace('\\', '/');
        String stem = shortName.endsWith(PNG_EXTENSION)
                ? shortName.substring(0, shortName.length() - PNG_EXTENSION.length())
                : shortName;
        Identifier rid = Identifier.fromNamespaceAndPath(
                ConfigModelLoader.CONFIG_NAMESPACE, TEXTURE_PATH_PREFIX + "/" + stem + PNG_EXTENSION);
        try (InputStream in = Files.newInputStream(pngFile)) {
            NativeImage img = NativeImage.read(in);
            DynamicTexture tex = new DynamicTexture(rid::toString, img);
            Minecraft.getInstance().getTextureManager().register(rid, tex);
            Constants.LOG.info("[animus-anim] registered config texture {}", rid);
        } catch (Exception ex) {
            Constants.LOG.error("[animus-anim] failed to load config texture {}: {}", pngFile, ex.toString());
        }
    }
}
