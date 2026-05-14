package com.dwinovo.animus;

import net.fabricmc.api.ModInitializer;

public class AnimusMod implements ModInitializer {

    @Override
    public void onInitialize() {

        Constants.LOG.info("Hello Fabric world!");
        CommonClass.init();
    }
}
