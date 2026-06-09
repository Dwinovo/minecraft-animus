package com.dwinovo.animus.client.agent;

import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Resolve an {@link AnimusEntity} on the client by its stable {@link UUID}.
 *
 * <h2>Why UUID, not network id</h2>
 * The vanilla network {@code entity.getId()} (int) is a per-session handle that
 * <strong>changes every time the entity is recreated</strong> — most notably on
 * cross-dimension travel, where a non-player entity is destroyed in the source
 * dimension and rebuilt as a fresh instance (new int id) in the destination,
 * keeping only its UUID. The client-side agent loop is therefore keyed by UUID
 * and resolves the current body through here, so it survives the int-id churn
 * of a Nether/End trip. See {@link AgentLoopRegistry}.
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

    /** The currently-loaded Animus body with this UUID, or {@code null}. */
    public static AnimusEntity resolve(UUID uuid) {
        if (uuid == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof AnimusEntity ae && uuid.equals(ae.getUUID())) {
                return ae;
            }
        }
        return null;
    }
}
