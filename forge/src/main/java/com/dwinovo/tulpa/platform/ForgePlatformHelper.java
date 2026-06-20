package com.dwinovo.tulpa.platform;

import com.dwinovo.tulpa.platform.services.IPlatformHelper;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Forge 1.20.4 implementation of {@link IPlatformHelper}. Mirrors the behaviour
 * of {@code FabricPlatformHelper} / the NeoForge reference, just against Forge's
 * {@code ModList} / {@code FMLPaths} / {@code FMLEnvironment}.
 */
public class ForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Forge";
    }

    @Override
    public java.nio.file.Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.production;
    }
}
