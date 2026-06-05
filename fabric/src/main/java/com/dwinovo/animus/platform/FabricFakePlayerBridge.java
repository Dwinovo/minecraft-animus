package com.dwinovo.animus.platform;

import com.dwinovo.animus.platform.services.IFakePlayerBridge;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric implementation of {@link IFakePlayerBridge}. Fabric API ships a shared,
 * cached {@link FakePlayer} per level/profile — {@code FakePlayer.get(level)}
 * returns the same instance each call, so it never accumulates.
 */
public final class FabricFakePlayerBridge implements IFakePlayerBridge {

    @Override
    public ServerPlayer getFakePlayer(ServerLevel level) {
        return FakePlayer.get(level);
    }
}
