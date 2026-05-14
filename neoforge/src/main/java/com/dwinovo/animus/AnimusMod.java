package com.dwinovo.animus;


import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(Constants.MOD_ID)
public class AnimusMod {

    public AnimusMod(IEventBus eventBus) {

        Constants.LOG.info("Hello NeoForge world!");
        CommonClass.init();

    }
}
