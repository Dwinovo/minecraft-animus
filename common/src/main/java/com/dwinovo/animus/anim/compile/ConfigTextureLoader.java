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
 * Loads {@code texture.png} from each per-model directory under
 * {@code <gameDir>/config/animus/models/<id>/} and registers each one with
 * Minecraft's {@code TextureManager} under
 * {@code animus_user:textures/entities/<id>.png} — the same path the
 * renderer derives from a model key via
 * {@link com.dwinovo.animus.anim.render.AnimusEntityRenderer#textureFor}.
 *
 * <p>This is the client-side companion of {@link ConfigModelLoader}.
 * Bedrock model files in the config tree reference their texture path
 * through the geometry / animation pair, but the texture sheet itself
 * lives outside the resource pack so this loader handles it explicitly.
 *
 * <p>Re-uploading on every reload: each scan walks the config dir and
 * registers every texture, overwriting prior entries. Minecraft's
 * {@code TextureManager} handles the prior entry's release internally.
 * Releasing manually after a delete would be cleaner, but the leaked
 * memory is bounded by the number of config textures (usually a handful).
 */
public final class ConfigTextureLoader {

    private ConfigTextureLoader() {}

    public static void scan(Path configDir) {
        if (configDir == null || !Files.isDirectory(configDir)) return;
        try (Stream<Path> dirs = Files.list(configDir)) {
            dirs.filter(Files::isDirectory).forEach(modelDir -> {
                Path texPath = modelDir.resolve(ConfigModelLoader.TEXTURE_FILE);
                if (!Files.isRegularFile(texPath)) return;
                register(modelDir.getFileName().toString(), texPath);
            });
        } catch (IOException ex) {
            Constants.LOG.error("[animus-anim] failed to walk config models dir {}: {}",
                    configDir, ex.toString());
        }
    }

    private static void register(String id, Path texPath) {
        Identifier rid = Identifier.fromNamespaceAndPath(
                ConfigModelLoader.CONFIG_NAMESPACE,
                "textures/entities/" + id + ".png");
        try (InputStream in = Files.newInputStream(texPath)) {
            NativeImage img = NativeImage.read(in);
            DynamicTexture tex = new DynamicTexture(rid::toString, img);
            Minecraft.getInstance().getTextureManager().register(rid, tex);
            Constants.LOG.info("[animus-anim] registered config texture {}", rid);
        } catch (Exception ex) {
            Constants.LOG.error("[animus-anim] failed to load config texture {}: {}",
                    texPath, ex.toString());
        }
    }
}
