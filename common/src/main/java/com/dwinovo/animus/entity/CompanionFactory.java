package com.dwinovo.animus.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;

/**
 * Spawns and despawns companion {@link AnimusPlayer} bodies through the vanilla
 * player-join path — entirely public API, no loader-specific construction needed
 * (the only fake piece is {@link FakeConnection}, which is common).
 *
 * <p>{@link net.minecraft.server.players.PlayerList#placeNewPlayer} loads the
 * companion's playerdata {@code .dat} (restoring inventory / position / owner on
 * a respawn), adds it to the player list (→ chunk loading for free) and to the
 * level. {@link net.minecraft.server.players.PlayerList#remove} saves that data
 * back and removes the body — so despawn is a clean, persisted dormancy.
 */
public final class CompanionFactory {

    private CompanionFactory() {}

    /**
     * Bring a companion into the world. On first creation pass a {@code pos}
     * (the spawn location, e.g. beside the owner); on a respawn from dormancy
     * pass {@code null} to keep the position restored from its {@code .dat}.
     */
    public static AnimusPlayer spawn(MinecraftServer server, UUID companionUuid, String name,
                                     UUID ownerUuid, ServerLevel level, Vec3 pos) {
        GameProfile profile = new GameProfile(companionUuid, name);
        AnimusPlayer player = new AnimusPlayer(server, level, profile, ClientInformation.createDefault());
        FakeConnection connection = new FakeConnection();
        server.getPlayerList().placeNewPlayer(connection, player,
                CommonListenerCookie.createInitial(profile, false));
        // First spawn has no .dat to restore the owner from; set it explicitly.
        if (player.getOwnerUuid() == null) {
            player.setOwnerUuid(ownerUuid);
        }
        if (pos != null) {
            player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), player.getYRot(), player.getXRot(), false);
        }
        return player;
    }

    /** Save the companion's data and remove it from the world (dormancy). */
    public static void despawn(MinecraftServer server, AnimusPlayer player) {
        server.getPlayerList().remove(player);
    }
}
