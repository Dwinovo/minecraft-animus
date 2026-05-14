package com.dwinovo.animus.platform;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.platform.services.IAnimusConfig;
import com.dwinovo.animus.platform.services.INetworkChannel;
import com.dwinovo.animus.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

public class Services {

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);
    public static final INetworkChannel NETWORK = load(INetworkChannel.class);
    public static final IAnimusConfig CONFIG = load(IAnimusConfig.class);

    public static <T> T load(Class<T> clazz) {

        final T loadedService = ServiceLoader.load(clazz, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}
