package com.dwinovo.animus.entity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;

/**
 * Coordinates companion lifecycle on top of {@link CompanionFactory} (body
 * spawn/despawn) and {@link CompanionRegistry} (the persistent index). The body
 * persists as a player {@code .dat}; the registry remembers it exists so it can
 * be recreated when its owner returns or a tool call arrives.
 */
public final class Companions {

    private Companions() {}

    /** Create a brand-new companion at {@code pos}, owned by {@code ownerUuid}. */
    public static AnimusPlayer summon(MinecraftServer server, UUID ownerUuid, String name,
                                      ServerLevel level, Vec3 pos) {
        UUID companionUuid = UUID.randomUUID();
        AnimusPlayer body = CompanionFactory.spawn(server, companionUuid, name, ownerUuid, level, pos);
        CompanionRegistry.get(server).put(companionUuid,
                new CompanionRegistry.Entry(name, ownerUuid, level.dimension(), body.blockPosition()));
        return body;
    }

    /**
     * Bring a dormant companion back from its catalog entry + {@code .dat}
     * (position/inventory restored from disk). Returns the already-live body if
     * it is spawned, or {@code null} if it is unknown to the registry.
     */
    public static AnimusPlayer respawn(MinecraftServer server, UUID companionUuid) {
        AnimusPlayer live = AnimusPlayer.findByUuid(server, companionUuid);
        if (live != null) return live;
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companionUuid);
        if (entry == null) return null;
        ServerLevel level = server.getLevel(entry.dimension());
        if (level == null) level = server.overworld();
        // pos=null: keep the position restored from the .dat.
        return CompanionFactory.spawn(server, companionUuid, entry.name(), entry.owner(), level, null);
    }

    /** When an owner logs in, bring back every companion of theirs not already live. */
    public static void respawnAllOwnedBy(MinecraftServer server, UUID ownerUuid) {
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            respawn(server, e.getKey());
        }
    }

    /** Save the companion to its {@code .dat} and remove it from the world (dormancy). */
    public static void dormant(MinecraftServer server, AnimusPlayer body) {
        // Refresh the respawn hint before the body leaves.
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry prev = reg.find(body.getUUID());
        if (prev != null) {
            reg.put(body.getUUID(), new CompanionRegistry.Entry(
                    prev.name(), prev.owner(),
                    ((ServerLevel) body.level()).dimension(), body.blockPosition()));
        }
        CompanionFactory.despawn(server, body);
    }

    /** Permanently forget a companion (death / dismissal): despawn + drop the index entry. */
    public static void dismiss(MinecraftServer server, AnimusPlayer body) {
        UUID uuid = body.getUUID();
        CompanionFactory.despawn(server, body);
        CompanionRegistry.get(server).remove(uuid);
    }
}
