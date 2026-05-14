package com.dwinovo.animus.render;

import com.dwinovo.animus.anim.render.AnimusEntityRenderer;
import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * Concrete entity renderer for {@link AnimusEntity}. The model identity is
 * resolved per-frame from {@link AnimusEntity#getModelKey()} — each entity
 * can render with a different baked model, swapped through the model-chooser
 * GUI (right-click while sneaking).
 */
public class AnimusRenderer extends AnimusEntityRenderer<AnimusEntity> {

    public AnimusRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }
}
