package com.dwinovo.animus.platform;

import com.dwinovo.animus.platform.services.IFakePlayerBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * NeoForge implementation of {@link IFakePlayerBridge}. {@code FakePlayerFactory}
 * caches fake players by profile; {@code getMinecraft(level)} returns a shared
 * instance with a fixed UUID, so it never accumulates (this avoids the
 * per-call-UUID fake-player memory leak NeoForge warns about).
 */
public final class NeoForgeFakePlayerBridge implements IFakePlayerBridge {

    @Override
    public ServerPlayer getFakePlayer(ServerLevel level) {
        return FakePlayerFactory.getMinecraft(level);
    }
}
