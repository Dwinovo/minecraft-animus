package com.dwinovo.tulpa.entity;

import com.dwinovo.tulpa.network.payload.TulpaDeathPayload;
import com.dwinovo.tulpa.network.payload.TulpaEventPayload;
import com.dwinovo.tulpa.network.payload.TulpaRespawnPayload;
import com.dwinovo.tulpa.network.payload.CompanionListPayload;
import com.dwinovo.tulpa.platform.Services;
import com.dwinovo.tulpa.task.CompanionTickDispatcher;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates companion lifecycle on top of {@link CompanionFactory} (body
 * spawn/despawn) and {@link CompanionRegistry} (the persistent index). The body
 * persists as a player {@code .dat}; the registry remembers it exists so it can
 * be recreated when its owner returns or a tool call arrives.
 */
public final class Companions {

    /** Ticks a dead companion stays down before respawning at its owner (~30 s). */
    private static final long RESPAWN_DELAY_TICKS = 30 * 20;

    private Companions() {}

    /**
     * Summon the owner's companion called {@code name}. IDEMPOTENT per (owner, name): if one already
     * exists it is reused — already live → returned as-is; dormant → brought back. Only a name with no
     * existing companion mints a fresh one. (The old "fresh random UUID every summon" minted same-name
     * duplicates that all respawned on login — that's the duplicate-companion bug.)
     */
    public static TulpaPlayer summon(MinecraftServer server, UUID ownerUuid, String name,
                                      ServerLevel level, Vec3 pos) {
        UUID existing = findByOwnerName(server, ownerUuid, name);
        if (existing != null) {
            TulpaPlayer body = respawn(server, existing);
            if (body != null) return body;
            CompanionRegistry.get(server).remove(existing);   // stale entry (no .dat) — replace it
        }
        UUID companionUuid = UUID.randomUUID();
        TulpaPlayer body = CompanionFactory.spawn(server, companionUuid, name, ownerUuid, level, pos);
        CompanionRegistry.get(server).put(companionUuid,
                new CompanionRegistry.Entry(name, ownerUuid, level.dimension(), body.blockPosition()));
        return body;
    }

    /** Companion UUID of the owner's companion named {@code name}, or null if none. */
    private static UUID findByOwnerName(MinecraftServer server, UUID ownerUuid, String name) {
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            if (e.getValue().name().equals(name)) return e.getKey();
        }
        return null;
    }

    /**
     * Bring a dormant companion back from its catalog entry + {@code .dat}
     * (position/inventory restored from disk). Returns the already-live body if
     * it is spawned, or {@code null} if it is unknown to the registry.
     */
    public static TulpaPlayer respawn(MinecraftServer server, UUID companionUuid) {
        TulpaPlayer live = TulpaPlayer.findByUuid(server, companionUuid);
        if (live != null) return live;
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companionUuid);
        if (entry == null) return null;
        ServerLevel level = server.getLevel(entry.dimension());
        if (level == null) level = server.overworld();
        // pos=null: keep the position restored from the .dat.
        return CompanionFactory.spawn(server, companionUuid, entry.name(), entry.owner(), level, null);
    }

    /** When an owner logs in, bring back every companion of theirs. A companion that DIED while the owner
     *  was away (death state persisted in the registry — survives the logout) is respawned-at-owner now
     *  AND told why it died; a live one is just restored from its {@code .dat}. */
    public static void respawnAllOwnedBy(MinecraftServer server, UUID ownerUuid) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            if (e.getValue().diedAt() > 0L) {
                if (owner != null) respawnDead(server, e.getKey(), e.getValue(), owner);
            } else {
                respawn(server, e.getKey());
            }
        }
    }

    /**
     * A companion just DIED (detected in {@link TulpaPlayer#tick}). The death itself is left fully
     * vanilla — drops / a grave mod / keepInventory all run because it's a real ServerPlayer death.
     * We only: stop the brain (the owner's loop suspends on {@link TulpaDeathPayload}, resolving the
     * in-flight tool call with the death cause), heal the body so its saved {@code .dat} is whole, and
     * queue a timed respawn at the owner. The corpse is removed AFTER this tick (a fake player isn't
     * auto-removed on death — it would sit at 0 HP forever waiting for a respawn packet that never comes).
     */
    public static void onDeath(TulpaPlayer body) {
        MinecraftServer server = body.level().getServer();
        if (server == null) return;
        UUID uuid = body.getUUID();
        String cause = body.getCombatTracker().getDeathMessage().getString();
        if (cause == null || cause.isBlank()) cause = "未知原因";
        CompanionTickDispatcher.clearActiveTask(body);   // no result shipped — the death payload drives the client
        ServerPlayer owner = body.resolveOwnerPlayer();
        if (owner != null) {   // immediate, same-session (carries the respawn delay for the client countdown)
            Services.NETWORK.sendToPlayer(owner, new TulpaDeathPayload(uuid, cause, RESPAWN_DELAY_TICKS * 50L));
        }
        // Persist the death (cause + game-time) in the world-saved registry so it survives a logout during
        // the respawn window — without this, a relog lost the pending state and the body silently respawned
        // "alive" with an empty inventory and no idea it had died.
        CompanionRegistry.get(server).markDead(uuid, cause, server.overworld().getGameTime());
        body.setHealth(body.getMaxHealth());             // saved .dat is a healthy body for the respawn
        server.execute(() -> CompanionFactory.despawn(server, body));   // remove the corpse safely after the tick
    }

    /**
     * Bring back any companion whose post-death timer has elapsed, AT ITS OWNER (covers dimension
     * follow too — it returns in whatever dimension the owner is now in). Owner offline → keep waiting
     * (their client-side brain can't run anyway); it respawns the moment they're back. Called each tick.
     */
    public static void tickRespawns(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).pendingDead()) {
            CompanionRegistry.Entry entry = e.getValue();
            if (now - entry.diedAt() < RESPAWN_DELAY_TICKS) continue;
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.owner());
            if (owner == null) continue;                            // owner offline — wait for login
            respawnDead(server, e.getKey(), entry, owner);
        }
    }

    /** Respawn a dead companion at its owner, clear the death state, and tell the brain it died + why
     *  (the cause rides the respawn payload, so it works even after a logout cleared the client's memory). */
    private static void respawnDead(MinecraftServer server, UUID uuid, CompanionRegistry.Entry entry,
                                    ServerPlayer owner) {
        ServerLevel level = (ServerLevel) owner.level();
        TulpaPlayer body = CompanionFactory.spawn(server, uuid, entry.name(), entry.owner(), level, owner.position());
        body.setHealth(body.getMaxHealth());
        body.clearFire();
        CompanionRegistry.get(server).markAlive(uuid);
        syncRosterToOwner(server, owner);
        Services.NETWORK.sendToPlayer(owner, new TulpaRespawnPayload(uuid, entry.deathCause()));
    }

    /**
     * Push a snapshot of the owner's companions <em>currently live in the world</em>
     * (UUID + name) to their client, so the G panel reflects what actually exists
     * rather than a persisted list. The server is the only place that can answer
     * "which in-world players are TulpaPlayers owned by you" — owner is a
     * server-side field — so it does the detection and ships the result. The
     * client treats each push as a complete replacement. Call after any change to
     * the live set (login-respawn, summon, despawn, death).
     */
    public static void syncRosterToOwner(MinecraftServer server, ServerPlayer owner) {
        List<CompanionListPayload.Entry> list = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof TulpaPlayer a && a.isOwnedByPlayer(owner.getUUID())) {
                list.add(new CompanionListPayload.Entry(a.getUUID(), a.getName().getString()));
            }
        }
        Services.NETWORK.sendToPlayer(owner, new CompanionListPayload(list));
    }

    /**
     * Push an async world {@code <event>} to the companion's brain (it runs on the owner's client).
     * {@code urgent} wakes an idle brain to react now; otherwise it rides along on the next owner turn.
     * No-op if the owner is offline (no client to receive it).
     */
    public static void emitEvent(TulpaPlayer body, String xml, boolean urgent) {
        ServerPlayer owner = body.resolveOwnerPlayer();
        if (owner != null) {
            Services.NETWORK.sendToPlayer(owner, new TulpaEventPayload(body.getUUID(), xml, urgent));
        }
    }

    /**
     * The companion crossed into a new dimension — on its OWN (it travels where it likes, not tied to
     * the owner). Tell its brain, ambient: it rides along on the next owner-driven turn rather than
     * spending a fresh LLM call just to note the move. Called from each loader's dimension-change hook.
     */
    public static void onDimensionChanged(TulpaPlayer body) {
        String dim = body.level().dimension().identifier().toString();
        emitEvent(body, "<event kind=\"dimension_change\" to=\"" + dim + "\">你进入了 " + dim
                + "。留意这个维度的环境和危险。</event>", false);
    }

    /** Save the companion to its {@code .dat} and remove it from the world (dormancy). */
    public static void dormant(MinecraftServer server, TulpaPlayer body) {
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
    public static void dismiss(MinecraftServer server, TulpaPlayer body) {
        UUID uuid = body.getUUID();
        CompanionFactory.despawn(server, body);
        CompanionRegistry.get(server).remove(uuid);
    }

    /**
     * Permanently dismiss EVERY companion of {@code ownerUuid} named {@code name} — gone for good, it
     * will NOT come back on login. Removes both live bodies and registry entries, so it also cleans up
     * any same-name duplicates that the old non-idempotent summon left behind. Returns how many it
     * dismissed. (The {@code .dat} files orphan harmlessly — with no registry entry nothing respawns
     * them.)
     */
    public static int dismissByName(MinecraftServer server, UUID ownerUuid, String name) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        List<UUID> ids = new ArrayList<>();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : reg.ownedBy(ownerUuid)) {
            if (e.getValue().name().equals(name)) ids.add(e.getKey());
        }
        // Defensive: also catch a live body of that name somehow missing from the registry.
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof TulpaPlayer a && a.isOwnedByPlayer(ownerUuid)
                    && a.getName().getString().equals(name) && !ids.contains(a.getUUID())) {
                ids.add(a.getUUID());
            }
        }
        for (UUID id : ids) {
            TulpaPlayer live = TulpaPlayer.findByUuid(server, id);
            if (live != null) CompanionFactory.despawn(server, live);
            reg.remove(id);
        }
        return ids.size();
    }
}
