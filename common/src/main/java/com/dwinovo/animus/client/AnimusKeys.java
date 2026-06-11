package com.dwinovo.animus.client;

import com.dwinovo.animus.client.screen.RosterScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Shared key mappings: defined once here, registered by each loader's client
 * init (Fabric {@code KeyMappingHelper} / NeoForge {@code RegisterKeyMappingsEvent}),
 * polled once per client tick via {@link #tick()}.
 */
public final class AnimusKeys {

    /** G — open the companion roster panel (or straight into chat with a single pet). */
    public static final KeyMapping OPEN_ROSTER = new KeyMapping(
            com.dwinovo.animus.data.ModLanguageData.Keys.KEY_OPEN_ROSTER,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
            KeyMapping.Category.MISC);

    private AnimusKeys() {}

    /** Per-client-tick poll; key presses only register while no screen is open. */
    public static void tick() {
        while (OPEN_ROSTER.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.screen == null) {
                RosterScreen.openHotkey();
            }
        }
    }
}
