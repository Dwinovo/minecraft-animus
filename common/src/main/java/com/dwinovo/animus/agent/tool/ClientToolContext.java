package com.dwinovo.animus.agent.tool;

import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * Per-turn context handed to local tools. Carries the "anchor" entity for the
 * agent — the {@code AnimusEntity} body the loop drives, plus its stable
 * {@code entity.getUUID()}. Perception tools call {@link #anchor()} to get the
 * perspective centre.
 *
 * <p>The entity may be {@code null} when it's unloaded out of view distance;
 * perception tools must check and surface a clean failure rather than NPE.
 */
public record ClientToolContext(AnimusEntity entity, UUID entityUuid) {

    /** The entity's own perspective is the scan centre / inventory anchor. */
    public LivingEntity anchor() {
        return entity;
    }
}
