package com.dwinovo.animus.client.agent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Resolve a companion's <strong>client-side body</strong> by its stable
 * {@link UUID}. The companion is a server-side fake {@code ServerPlayer}; on a
 * tracking client it materialises as a {@link AbstractClientPlayer}
 * ({@code RemotePlayer}) in the loaded entity list, exactly like any other
 * player. So "find the companion" is "find the player entity with this UUID."
 *
 * <h2>Why UUID, not network id</h2>
 * The vanilla network {@code entity.getId()} (int) is a per-session handle that
 * changes whenever the entity is recreated (most notably on cross-dimension
 * travel). The client agent loop is therefore keyed by UUID and resolves the
 * current body through here, surviving the int-id churn of a Nether/End trip.
 * See {@link AgentLoopRegistry}.
 *
 * <p>{@code ClientLevel} exposes no public by-UUID lookup, so we scan the
 * loaded entities. Callers (chat GUI, agent loop) hit this infrequently — per
 * prompt / per tool round-trip, never per tick — so the linear scan is fine.
 *
 * <h2>Threading</h2>
 * Client main thread only.
 */
public final class ClientAnimusLookup {

    private ClientAnimusLookup() {}

    /** The currently-loaded companion body with this UUID, or {@code null}. */
    public static AbstractClientPlayer resolve(UUID uuid) {
        if (uuid == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof AbstractClientPlayer p && uuid.equals(p.getUUID())) {
                return p;
            }
        }
        return null;
    }
}
