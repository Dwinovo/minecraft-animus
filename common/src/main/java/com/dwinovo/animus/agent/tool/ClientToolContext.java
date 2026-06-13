package com.dwinovo.animus.agent.tool;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * Per-turn context handed to local tools. Carries the "anchor" body for the
 * agent — the companion's client-side {@link AbstractClientPlayer} the loop
 * drives, plus its stable {@code getUUID()}. Perception tools call
 * {@link #anchor()} to get the perspective centre.
 *
 * <p>The body may be {@code null} when it's unloaded out of view distance;
 * perception tools must check and surface a clean failure rather than NPE.
 */
public record ClientToolContext(AbstractClientPlayer entity, UUID entityUuid) {

    /** The body's own perspective is the scan centre / inventory anchor. */
    public LivingEntity anchor() {
        return entity;
    }
}
