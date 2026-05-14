package com.dwinovo.animus.anim.render.layer;

/**
 * Visual layer that runs <em>after</em> the main mesh has been submitted, in
 * registration order. Layers compose held items, prop attachments, emissive
 * overlays, capes, and similar accessories on top of the baked geometry
 * without touching the main render path.
 *
 * <p>Each layer receives a {@link RenderLayerContext} that already includes
 * the entity-rooted PoseStack and the post-interceptor pose buffer; layers
 * are responsible for their own {@code pushPose/popPose} balance and should
 * never mutate the pose buffer (interceptors, not layers, own that channel).
 *
 * <p>Layers are deliberately stateless aside from immutable configuration
 * (e.g. a target bone name passed to the constructor). State that needs to
 * survive between frames belongs on {@link com.dwinovo.animus.anim.render.AnimusRenderState}
 * (extract-time snapshot) or in the entity itself.
 */
@FunctionalInterface
public interface RenderLayer {

    void submit(RenderLayerContext ctx);
}
