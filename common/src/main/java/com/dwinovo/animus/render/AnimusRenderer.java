package com.dwinovo.animus.render;

import com.dwinovo.animus.anim.render.AnimusEntityRenderer;
import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * Concrete entity renderer for {@link AnimusEntity}. The model identity is
 * hardcoded to {@code "hachiware"} for this stage; once the player-customisable
 * model selection lands (a synced {@code EntityDataAccessor<String> MODEL_KEY}
 * field on the entity plus a config / GUI to pick from
 * {@code <gameDir>/config/animus/models/}), this renderer will read the key
 * from {@link com.dwinovo.animus.anim.render.AnimusRenderState} instead.
 */
public class AnimusRenderer extends AnimusEntityRenderer<AnimusEntity> {

    public AnimusRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, "hachiware");
    }
}
