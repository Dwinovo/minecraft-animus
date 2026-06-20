package com.dwinovo.tulpa.client.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.20.1 shim for the GUI sprite-atlas API ({@code GuiGraphics.blitSprite}), which only exists on
 * 1.20.2+. The mod's UI declares sprites as {@code tulpa:<name>} ids backed by
 * {@code textures/gui/sprites/<name>.png} (+ an optional {@code .png.mcmeta} 9-slice descriptor),
 * exactly the post-1.20.2 sprite layout. Here we resolve those by hand: read the PNG's pixel size
 * from its header and the {@code nine_slice} border from the mcmeta, then draw with 1.20.1's
 * direct-texture {@link GuiGraphics#blitNineSliced} / {@link GuiGraphics#blit}. Metadata is cached
 * per sprite. Call sites use {@code GuiCompat.blitSprite(g, SPRITE, x, y, w, h)} in place of
 * {@code g.blitSprite(SPRITE, x, y, w, h)}.
 */
public final class GuiCompat {

    private GuiCompat() {}

    private record SpriteMeta(int texW, int texH, int border, boolean nineSlice) {}

    private static final Map<ResourceLocation, SpriteMeta> CACHE = new ConcurrentHashMap<>();

    public static void blitSprite(GuiGraphics g, ResourceLocation spriteId, int x, int y, int w, int h) {
        ResourceLocation tex = new ResourceLocation(spriteId.getNamespace(),
                "textures/gui/sprites/" + spriteId.getPath() + ".png");
        SpriteMeta m = CACHE.computeIfAbsent(spriteId, id -> load(tex));
        if (m.nineSlice) {
            g.blitNineSliced(tex, x, y, w, h, m.border, m.texW, m.texH, m.texW, m.texH);
        } else {
            // Stretch the whole texture into the requested w×h box.
            g.blit(tex, x, y, w, h, 0.0F, 0.0F, m.texW, m.texH, m.texW, m.texH);
        }
    }

    private static SpriteMeta load(ResourceLocation tex) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        int texW = 16;
        int texH = 16;
        try (InputStream in = rm.open(tex)) {
            int[] dim = pngSize(in);
            texW = dim[0];
            texH = dim[1];
        } catch (Exception ignored) {
            // missing/unreadable texture → fall back to 16×16; the blit will just be wrong-sized, not crash
        }

        int border = 0;
        boolean nine = false;
        ResourceLocation mcmeta = new ResourceLocation(tex.getNamespace(), tex.getPath() + ".mcmeta");
        try (InputStream in = rm.open(mcmeta)) {
            JsonObject scaling = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("gui").getAsJsonObject("scaling");
            if ("nine_slice".equals(scaling.get("type").getAsString())) {
                nine = true;
                border = scaling.get("border").getAsInt();
                // The mcmeta width/height is the sprite's logical (source) size — authoritative for
                // the 9-slice math; the PNG is the same size, so this just confirms texW/texH.
                if (scaling.has("width")) texW = scaling.get("width").getAsInt();
                if (scaling.has("height")) texH = scaling.get("height").getAsInt();
            }
        } catch (Exception ignored) {
            // no mcmeta (or not nine_slice) → plain stretch blit
        }
        return new SpriteMeta(texW, texH, border, nine);
    }

    /** Read width/height from a PNG's IHDR header (first 24 bytes) without decoding the image. */
    private static int[] pngSize(InputStream in) throws IOException {
        byte[] b = in.readNBytes(24);
        if (b.length < 24) {
            throw new IOException("short PNG header");
        }
        int w = ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
        int h = ((b[20] & 0xFF) << 24) | ((b[21] & 0xFF) << 16) | ((b[22] & 0xFF) << 8) | (b[23] & 0xFF);
        return new int[]{w, h};
    }
}
