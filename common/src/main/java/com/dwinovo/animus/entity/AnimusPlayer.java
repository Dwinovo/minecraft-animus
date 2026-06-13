package com.dwinovo.animus.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

/**
 * The companion body: a server-side fake {@link ServerPlayer}. Replaces the old
 * custom {@code AnimusEntity} Mob so the companion is a first-class player —
 * native interaction/combat code paths (universal mod compatibility), its own
 * player inventory, and free chunk loading + playerdata persistence by virtue of
 * being a list-resident player.
 *
 * <h2>Identity &amp; ownership</h2>
 * Created by {@link CompanionFactory} with a stable per-companion UUID (carried
 * in the {@link GameProfile}); the enumerable index lives in
 * {@link CompanionRegistry}. Unlike the Mob, a fake player cannot carry custom
 * {@code SynchedEntityData}, so the owner is a plain server-side field persisted
 * to the companion's own playerdata {@code .dat} via
 * {@link #addAdditionalSaveData}. Owner checks are UUID comparisons — never
 * vanilla {@code isOwnedBy} (which resolves through a level and breaks across
 * dimensions).
 */
public final class AnimusPlayer extends ServerPlayer {

    private static final String NBT_KEY_OWNER = "AnimusOwner";

    /** Owner's player UUID. Null only transiently before the first assignment. */
    private UUID ownerUuid;

    public AnimusPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
                        ClientInformation clientInformation) {
        super(server, level, profile, clientInformation);
    }

    /** The loaded companion body with this UUID, or {@code null} if not spawned. */
    public static AnimusPlayer findByUuid(MinecraftServer server, UUID uuid) {
        return server.getPlayerList().getPlayer(uuid) instanceof AnimusPlayer ap ? ap : null;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    /** Cross-dimension safe owner check — UUID comparison, not level-scoped lookup. */
    public boolean isOwnedByPlayer(UUID playerUuid) {
        return ownerUuid != null && ownerUuid.equals(playerUuid);
    }

    /** The owner as an online player, server-wide; null when offline. */
    public ServerPlayer resolveOwnerPlayer() {
        return ownerUuid == null ? null : level().getServer().getPlayerList().getPlayer(ownerUuid);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (ownerUuid != null) {
            output.store(NBT_KEY_OWNER, UUIDUtil.CODEC, ownerUuid);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read(NBT_KEY_OWNER, UUIDUtil.CODEC).ifPresent(uuid -> this.ownerUuid = uuid);
    }
}
