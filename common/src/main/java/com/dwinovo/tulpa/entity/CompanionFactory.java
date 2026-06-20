package com.dwinovo.tulpa.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;

/**
 * Spawns and despawns companion {@link TulpaPlayer} bodies through the vanilla
 * player-join path — entirely public API, no loader-specific construction needed
 * (the only fake piece is {@link FakeConnection}, which is common).
 *
 * <p>{@link net.minecraft.server.players.PlayerList#placeNewPlayer} adds the body
 * to the player list (→ chunk loading for free) and to the level, but does NOT
 * load a hand-built fake player's {@code .dat} (that path is tied to the real
 * login flow) — so {@link #spawn} restores position / inventory / owner from disk
 * explicitly afterwards, the way Carpet's {@code EntityPlayerMPFake} does.
 * {@link net.minecraft.server.players.PlayerList#remove} saves that data back and
 * removes the body — so despawn is a clean, persisted dormancy.
 */
public final class CompanionFactory {

    private CompanionFactory() {}

    /**
     * Bring a companion into the world. On first creation pass a {@code pos}
     * (the spawn location, e.g. beside the owner); on a respawn from dormancy
     * pass {@code null} to keep the position restored from its {@code .dat}.
     */
    public static TulpaPlayer spawn(MinecraftServer server, UUID companionUuid, String name,
                                     UUID ownerUuid, ServerLevel level, Vec3 pos) {
        GameProfile profile = new GameProfile(companionUuid, name);
        TulpaPlayer player = new TulpaPlayer(server, level, profile, ClientInformation.createDefault());
        FakeConnection connection = new FakeConnection();
        server.getPlayerList().placeNewPlayer(connection, player,
                CommonListenerCookie.createInitial(profile, false));
        // placeNewPlayer does NOT load a hand-built fake player's .dat, so restore
        // it ourselves (Carpet's model): position, inventory, health, owner from
        // disk. Without this a respawned companion spawns at 0,0,0 with no items.
        loadPlayerData(server, player);
        // Companions are always survival, whatever the world's default game type — their whole design
        // (gather/drops, real combat, recoverable death) is survival-shaped, and placeNewPlayer would
        // otherwise hand a creative world's body instabuild (no block drops, breaks auto_mine). Forced
        // here after the .dat restore so a stale saved game type can't override it.
        player.setGameMode(GameType.SURVIVAL);
        // First spawn has no .dat to restore the owner from; set it explicitly.
        if (player.getOwnerUuid() == null) {
            player.setOwnerUuid(ownerUuid);
        }
        // An explicit pos (fresh summon) overrides the restored position; a respawn
        // from dormancy passes null to keep exactly what the .dat restored.
        if (pos != null) {
            player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), player.getYRot(), player.getXRot());
        }
        return player;
    }

    /**
     * Restore a fake player's saved state from its playerdata {@code .dat}
     * ({@link net.minecraft.server.players.PlayerList#loadPlayerData} +
     * {@link net.minecraft.world.entity.Entity#load}). {@code placeNewPlayer}
     * skips this for hand-constructed players, so we do it like Carpet's
     * {@code loadPlayerData}. No-op on first summon (no file yet).
     */
    private static void loadPlayerData(MinecraftServer server, TulpaPlayer player) {
        // 1.21.5: PlayerList.load(player) returns Optional<CompoundTag> (predates the
        // ValueInput IO refactor); Entity.load(CompoundTag) consumes it directly.
        server.getPlayerList().load(player)
                .ifPresent(player::load);
    }

    /** Save the companion's data and remove it from the world (dormancy). */
    public static void despawn(MinecraftServer server, TulpaPlayer player) {
        // Finalize any running task first, so its teardown (e.g. clearing a mining
        // crack overlay) runs instead of being orphaned once the body leaves the
        // tick loop's player list.
        com.dwinovo.tulpa.task.CompanionTickDispatcher.onCompanionRemoved(player);
        server.getPlayerList().remove(player);
    }
}
