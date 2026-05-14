package com.dwinovo.animus.anim.render;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.anim.api.AnimationLibrary;
import com.dwinovo.animus.anim.api.AnimusAnimated;
import com.dwinovo.animus.anim.api.ModelLibrary;
import com.dwinovo.animus.anim.baked.BakedAnimation;
import com.dwinovo.animus.anim.baked.BakedModel;
import com.dwinovo.animus.anim.controller.BlendMode;
import com.dwinovo.animus.anim.controller.ControllerConfig;
import com.dwinovo.animus.anim.controller.ControllerHandler;
import com.dwinovo.animus.anim.controller.ControllerSnapshot;
import com.dwinovo.animus.anim.molang.MolangContext;
import com.dwinovo.animus.anim.render.layer.RenderLayer;
import com.dwinovo.animus.anim.render.layer.RenderLayerContext;
import com.dwinovo.animus.anim.runtime.AnimationClock;
import com.dwinovo.animus.anim.runtime.AnimationChannel;
import com.dwinovo.animus.anim.runtime.Animator;
import com.dwinovo.animus.anim.runtime.PoseMixer;
import com.dwinovo.animus.anim.runtime.PoseSampler;
import com.dwinovo.animus.anim.runtime.AnimContext;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base entity renderer for Bedrock-format Animus entities. Owns the controller
 * roster, the model lookup, the Bedrock entity transform, and per-frame pose
 * sampling.
 *
 * <h2>Controllers</h2>
 * Subclasses register {@link ControllerConfig}s in their constructor. The
 * base class registers a state-driven {@code "main"} controller that picks
 * between {@code idle} / {@code walk} based on movement speed, an ambient
 * {@code "blink"} loop, and {@code "action"} / {@code "reaction"} dummy
 * controllers for externally-triggered one-shot animations.
 *
 * <p>Controllers run in registration order; later controllers' samples
 * compose on top of earlier ones in the shared pose buffer per their
 * {@link BlendMode}.
 *
 * <h2>Identifier convention</h2>
 * {@code modelKey} is {@code namespace:path} (e.g. {@code animus:hachiware}).
 * The matching texture is at {@code textures/entities/<path>.png}, animation
 * names register at {@code <ns>:<path>/<anim_name>}.
 */
public abstract class AnimusEntityRenderer<T extends Entity> extends EntityRenderer<T, AnimusRenderState> {

    private static final float PIXEL_SCALE = 1.0f / 16.0f;
    /** Default base-loop animation name. */
    private static final String DEFAULT_LOOP_NAME = "idle";

    /** Controller name carrying state-driven base loop (idle/walk/run/sit/...). */
    public static final String CONTROLLER_MAIN = "main";
    /** Controller name receiving one-shot action triggers from {@link com.dwinovo.animus.entity.AnimusEntity}. */
    public static final String CONTROLLER_ACTION = "action";
    /** Controller name receiving one-shot reaction triggers. */
    public static final String CONTROLLER_REACTION = "reaction";
    /**
     * Controller name driving the ambient blink loop. Plays the {@code blink}
     * animation from each pet's animation file as a constant decorative loop.
     * Pets without a {@code blink} animation get a no-op (handler returns
     * {@code null} and the controller stays silent) — graceful degradation,
     * no per-pet code needed.
     */
    public static final String CONTROLLER_BLINK = "blink";
    /** Pets are 0.6 blocks wide; this matches vanilla small-mob shadow sizing. */
    private static final float DEFAULT_SHADOW_RADIUS = 0.4f;
    /**
     * Stop-fade duration applied to action / reaction controllers when their
     * triggered animation finishes. Lets the bones the trigger animated blend
     * smoothly back to whatever main wrote, instead of snapping in a single
     * frame. Trigger fade-<em>in</em> stays disabled (gameplay events should
     * feel responsive); only fade-<em>out</em> uses this value.
     */
    private static final float TRIGGER_STOP_FADE_SEC = 0.15f;

    private final ModelRenderer mesh = new ModelRenderer();
    private final Quaternionf rotBuf = new Quaternionf();
    private final MolangContext molangCtx = new MolangContext();
    /**
     * Per-pet controller registry, declared in subclass constructors and
     * frozen once the renderer is built. The same list is handed to every
     * entity's {@link Animator} on first extract — controllers themselves
     * are stateless config; per-entity mutable state lives in
     * {@link com.dwinovo.animus.anim.controller.ControllerInstance}.
     */
    private final List<ControllerConfig> controllerConfigs = new ArrayList<>();
    /**
     * Pre-sampling MoLang context fillers. Run in registration order before
     * {@code PoseSampler}, so any MoLang expression evaluated during sampling
     * sees the values it needs.
     */
    private final List<BoneInputProvider> inputProviders = new ArrayList<>();
    /**
     * Procedural pose-buffer overrides organised by stage. Stages run in
     * {@link BoneInterceptor.Stage} declaration order; within a stage,
     * interceptors run in registration order. Subclasses register additional
     * interceptors from their constructor via {@link #addInterceptor}.
     */
    private final EnumMap<BoneInterceptor.Stage, List<BoneInterceptor>> interceptors =
            new EnumMap<>(BoneInterceptor.Stage.class);
    /**
     * Visual layers that run after the main mesh submits, in registration order.
     * Subclasses register additional layers (props, emissive overlays, capes...)
     * via {@link #addRenderLayer(RenderLayer)} from their constructor.
     */
    private final List<RenderLayer> renderLayers = new ArrayList<>();
    /**
     * Per-bone visibility rules keyed by bone name. A bone with one or more
     * rules is visible when <em>any</em> rule votes {@code true}; absence of
     * a vote means the bone is hidden that frame. Bones never registered are
     * always visible (the {@link #hasVisibilityRules} fast-path skips
     * evaluation entirely when this map is empty).
     */
    private final Map<String, List<BoneVisibilityRule>> visibilityRules = new HashMap<>();

    protected AnimusEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = DEFAULT_SHADOW_RADIUS;

        // Default MoLang inputs.
        addInputProvider(new BasicMolangInputProvider());
        addInputProvider(new EntityMolangInputProvider());
        // Default interceptors shared by all pets. Subclasses can append more.
        // Head look-at is the only universal procedural override — every pet
        // tracks the player's view direction. Ear / tail sway used to live on
        // a hardcoded PHYSICS_SECONDARY interceptor; it was removed once
        // animator-authored ear/tail keyframes (in idle/walk/etc.) became the
        // single source of truth, so animation data isn't silently overwritten.
        addInterceptor(BoneInterceptor.Stage.LOOK_AT, new HeadLookInterceptor());

        // Default controllers shared by all Animus entities, in priority-from-low order:
        //   "main"     state-driven base loop
        //   "blink"    ambient eye-blink decorative loop (graceful no-op when
        //              the pet's animation file has no "blink" animation)
        //   "action"   externally-triggered one-shot semantic actions — runs
        //              after blink so a gameplay action's eye keyframes (if
        //              any) override ambient blinking
        //   "reaction" externally-triggered one-shot emotional reactions —
        //              same priority rationale as action
        // Sub-classes append further decorative or override controllers on top.
        addController(new ControllerConfig(
                CONTROLLER_MAIN,
                BlendMode.OVERRIDE,
                Animator.DEFAULT_BASE_TRANSITION_SEC,
                this::resolveMainAnimation));
        addLoopingController(CONTROLLER_BLINK, BlendMode.OVERRIDE, "blink");
        addController(new ControllerConfig(
                CONTROLLER_ACTION,
                BlendMode.OVERRIDE,
                TRIGGER_STOP_FADE_SEC,
                ControllerHandler::neverPlay));
        addController(new ControllerConfig(
                CONTROLLER_REACTION,
                BlendMode.OVERRIDE,
                TRIGGER_STOP_FADE_SEC,
                ControllerHandler::neverPlay));
    }

    /** Register a {@link BoneInputProvider} that fills MoLang context before sampling. */
    protected final void addInputProvider(BoneInputProvider provider) {
        inputProviders.add(provider);
    }

    /**
     * Register an additional {@link BoneInterceptor} into the named stage.
     * Stages run in {@link BoneInterceptor.Stage} declaration order.
     */
    protected final void addInterceptor(BoneInterceptor.Stage stage, BoneInterceptor interceptor) {
        interceptors.computeIfAbsent(stage, k -> new ArrayList<>()).add(interceptor);
    }

    /** Register an additional {@link RenderLayer}; runs after the main mesh in registration order. */
    protected final void addRenderLayer(RenderLayer layer) {
        renderLayers.add(layer);
    }

    /**
     * Append a controller to the per-entity roster. Registration order is
     * priority — controllers added later override / blend on top of earlier
     * ones in the shared pose buffer.
     *
     * <p>Two controllers must not share a name. Names are used by
     * {@link Animator#playOnce} to dispatch external one-shot triggers.
     */
    protected final void addController(ControllerConfig config) {
        for (ControllerConfig existing : controllerConfigs) {
            if (existing.name().equals(config.name())) {
                throw new IllegalStateException("duplicate controller name: " + config.name());
            }
        }
        controllerConfigs.add(config);
    }

    /**
     * Helper for subclasses to register a constant looping controller — the
     * common "blink / breath / tail-idle" pattern. The handler is a closure
     * over the animation lookup so a missing anim file (during dev) doesn't
     * crash the renderer.
     */
    protected final void addLoopingController(String name, BlendMode blendMode, String animationName) {
        // Resolve the animation key lazily against the per-frame state.modelKey
        // — entities can switch models at runtime, so the key isn't known here.
        addController(new ControllerConfig(name, blendMode, 0f,
                (state, ctx) -> AnimationLibrary.get(animKey(state.modelKey, animationName))));
    }

    /**
     * Register a per-frame visibility predicate for a named bone. The bone
     * (and its entire subtree) is hidden when no registered rule for that
     * bone votes visible.
     *
     * <p>This is the long-term home for "this bone shows when X" logic — see
     * {@link BoneVisibilityRule} for the spec / Bedrock-compatibility
     * justification. Registering multiple rules for the same bone composes
     * with OR semantics (any vote shows the bone).
     *
     * <p>Typical usage in a subclass constructor:
     * <pre>{@code
     * addBoneVisibilityRule("guitar",
     *     (state, ctx) -> isControllerPlaying(state, CONTROLLER_ACTION, "play_guitar"));
     * }</pre>
     */
    protected final void addBoneVisibilityRule(String boneName, BoneVisibilityRule rule) {
        visibilityRules.computeIfAbsent(boneName, k -> new ArrayList<>()).add(rule);
    }

    /** Whether any visibility rule has been registered — fast-path skip when none. */
    private boolean hasVisibilityRules() {
        return !visibilityRules.isEmpty();
    }

    /**
     * Helper for visibility rules: returns whether the named controller is
     * currently sampling the named animation (case-sensitive). Rules can use
     * this to express "bone visible when controller X plays animation Y" —
     * the most common pattern (held props, prop-specific bones).
     *
     * @param state          current render-state snapshot
     * @param controllerName e.g. {@link #CONTROLLER_ACTION}
     * @param animationName  short name (e.g. {@code "play_guitar"})
     */
    public static boolean isControllerPlaying(AnimusRenderState state,
                                              String controllerName, String animationName) {
        if (state == null || state.controllerSnapshots == null) return false;
        for (var snapshot : state.controllerSnapshots) {
            if (snapshot == null || !controllerName.equals(snapshot.name())) continue;
            if (snapshot.current() == null) return false;
            return animationName.equals(snapshot.current().animation().name);
        }
        return false;
    }

    /**
     * Visibility-rule helper: returns whether <em>any</em> controller is
     * currently sampling the named animation. Useful when the rule shouldn't
     * care which controller plays it (e.g. "show guitar bone whenever some
     * controller — main, action, whatever — plays the {@code guitar}
     * animation").
     *
     * <p>Checks both the controller's {@code current} and {@code previous}
     * channels. The {@code previous} check is what keeps a prop visible
     * during a fade-OUT crossfade — without it, the prop pops out the moment
     * the resolver picks a new animation, even though the body is still
     * mid-fade in the prop's pose. Equivalent to "is the named animation
     * still contributing to the pose buffer for any controller".
     */
    public static boolean isAnyControllerPlaying(AnimusRenderState state, String animationName) {
        if (state == null || state.controllerSnapshots == null) return false;
        for (var snapshot : state.controllerSnapshots) {
            if (snapshot == null) continue;
            if (snapshot.current() != null
                    && animationName.equals(snapshot.current().animation().name)) {
                return true;
            }
            if (snapshot.previous() != null
                    && animationName.equals(snapshot.previous().animation().name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public AnimusRenderState createRenderState() {
        return new AnimusRenderState();
    }

    @Override
    public void extractRenderState(T entity, AnimusRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);

        // Resolve the model identity from the entity (synced server → client),
        // falling back to the marker-interface default when the entity hasn't
        // overridden getModelKey. Every other render key (texture, render
        // controller, default-loop animation) is derived from this one.
        Identifier resolvedModelKey = entity instanceof AnimusAnimated animated
                ? animated.getModelKey() : AnimusAnimated.DEFAULT_MODEL_KEY;
        state.modelKey = resolvedModelKey;
        state.texture  = textureFor(resolvedModelKey);

        // Body rotation copied from LivingEntity — vanilla LivingEntityRenderState
        // owns this normally but EntityRenderer.extractRenderState does not.
        if (entity instanceof LivingEntity living) {
            float bodyRot = net.minecraft.util.Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot);
            float headRot = net.minecraft.util.Mth.rotLerp(partialTick, living.yHeadRotO, living.getYHeadRot());
            float pitch   = net.minecraft.util.Mth.rotLerp(partialTick, living.xRotO, living.getXRot());
            state.bodyRot = bodyRot;
            state.yRot = headRot;
            state.xRot = pitch;
            state.scale = living.getScale();
            state.ageInTicks = living.tickCount + partialTick;
            state.walkSpeed = living.walkAnimation.speed(partialTick);
            state.hasRedOverlay = living.hurtTime > 0 || living.deathTime > 0;
            state.deathTime = living.deathTime > 0 ? living.deathTime + partialTick : 0.0f;
            // Capture the head-look snapshot here so it survives the post-extract
            // bodyRot/yRot stomp performed by InventoryScreen.renderEntityInInventoryFollowsMouse.
            state.netHeadYaw = net.minecraft.util.Mth.wrapDegrees(headRot - bodyRot);
            state.headPitch  = pitch;
            // Bedrock-standard query.* feeds.
            state.isOnGround = living.onGround();
            state.isInWater  = living.isInWater();
            state.health     = living.getHealth();
            state.maxHealth  = living.getMaxHealth();
        }

        if (entity instanceof AnimusAnimated animated) {
            state.entityTaskHash = com.dwinovo.animus.anim.molang.MolangQueries.hashString(animated.getCurrentTask());

            Animator animator = animated.getAnimator();
            animator.ensureInitialised(controllerConfigs);
            long nowNs = AnimationClock.fromTicks(entity.tickCount, partialTick);
            state.animationTimeNs = nowNs;
            animator.setPhaseSeed(entity.getUUID().getLeastSignificantBits(), nowNs);

            // Drop any controller still holding a pre-reload BakedAnimation.
            BakedModel currentModel = ModelLibrary.get(state.modelKey);
            if (currentModel != null) {
                animator.clearStale(currentModel.bakeStamp);
            }

            AnimContext ctx = AnimContext.empty();
            animator.tick(state, ctx, nowNs);
            state.controllerSnapshots = animator.snapshot();
        }
    }

    @Override
    protected float getShadowRadius(AnimusRenderState state) {
        return super.getShadowRadius(state) * state.scale;
    }

    /**
     * Compose Java-lambda visibility rules with Molang-driven {@code part_visibility}
     * rules into {@link AnimusRenderState#hiddenBones}. Called from {@link #submit}
     * after the per-frame {@link MolangContext} has been filled — Molang
     * expressions referencing {@code entity.task} / {@code query.ground_speed}
     * see the live values.
     *
     * <p>Java lambdas run first (OR within), then the baked render controller's
     * rules overwrite where they match (last-write-wins per Bedrock).
     */
    private void evaluateVisibilityRules(AnimusRenderState state, BakedModel model) {
        com.dwinovo.animus.anim.baked.BakedRenderController rc =
                state.modelKey == null ? null
                : com.dwinovo.animus.anim.api.RenderControllerLibrary.get(state.modelKey);
        boolean hasJava = hasVisibilityRules();
        boolean hasMolang = rc != null && !rc.rules.isEmpty();

        if (!hasJava && !hasMolang) {
            state.hiddenBones = EMPTY_HIDDEN_BONES;
            return;
        }
        boolean[] hidden = new boolean[model.bones.length];
        if (hasJava) {
            BoneVisibility.applyJavaRules(visibilityRules, model, state, AnimContext.empty(), hidden);
        }
        if (hasMolang) {
            rc.applyTo(model, molangCtx, hidden);
        }
        state.hiddenBones = hidden;
    }

    private static final boolean[] EMPTY_HIDDEN_BONES = new boolean[0];

    /**
     * Default {@code "main"} controller handler — picks {@code walk} when the
     * entity is moving (above the idle cutoff) and {@code idle} otherwise.
     * Falls back gracefully when {@code walk} is absent from the animation
     * library (model with only an idle authored).
     *
     * <p>Subclasses override this to add intermediate animations (run, sit,
     * activity-driven loops) once the corresponding gameplay state plumbing
     * lands. For the LLM-driven phase, the LLM dispatches via the
     * {@code "action"} controller through {@link Animator#playOnce}.
     */
    protected BakedAnimation resolveMainAnimation(AnimusRenderState state, AnimContext ctx) {
        if (state.walkSpeed > 0.15f) {
            BakedAnimation walk = AnimationLibrary.get(animKey(state.modelKey, "walk"));
            if (walk != null) return walk;
        }
        return AnimationLibrary.get(animKey(state.modelKey, DEFAULT_LOOP_NAME));
    }

    /** Resolve {@code <modelKey>:<animName>} — the global animation library key. */
    public static Identifier animKey(Identifier modelKey, String name) {
        return Identifier.fromNamespaceAndPath(modelKey.getNamespace(),
                modelKey.getPath() + "/" + name);
    }

    /** Derive the texture path for a given model identifier. */
    public static Identifier textureFor(Identifier modelKey) {
        return Identifier.fromNamespaceAndPath(modelKey.getNamespace(),
                "textures/entities/" + modelKey.getPath() + ".png");
    }

    /** First animation in the candidate list that exists in the library, or {@code null}. */
    protected final BakedAnimation firstAvailable(Identifier modelKey, Iterable<String> names) {
        for (String name : names) {
            BakedAnimation animation = AnimationLibrary.get(animKey(modelKey, name));
            if (animation != null) return animation;
        }
        return null;
    }

    @Override
    public void submit(AnimusRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        BakedModel model = ModelLibrary.get(state.modelKey);
        if (model == null) return;

        // Pose buffer is allocated per-submit because submitCustomGeometry may
        // defer the draw lambda; a renderer-shared buffer would be overwritten
        // by the next entity's submit before this one's lambda runs.
        int boneCount = model.bones.length;
        float[] poseBuf = new float[boneCount * PoseSampler.FLOATS_PER_BONE];
        PoseSampler.resetIdentity(poseBuf, boneCount);

        // Frame-level MoLang context. query.anim_time is filled per-channel
        // by PoseSampler; the input providers fill the rest.
        molangCtx.reset();
        for (BoneInputProvider provider : inputProviders) {
            provider.fill(state, molangCtx);
        }

        // Resolve per-bone visibility here (after the molang context is filled
        // by the input providers) so render_controllers.json expressions like
        // `entity.task == 'play_music'` see the current values.
        evaluateVisibilityRules(state, model);

        long nowNs = state.animationTimeNs;
        // Iterate controllers in registration order. Each controller writes
        // into the shared pose buffer per its blend mode — later controllers
        // override or blend on top of earlier ones.
        for (ControllerSnapshot snapshot : state.controllerSnapshots) {
            sampleController(snapshot, nowNs, molangCtx, poseBuf, boneCount);
        }

        // Procedural overrides (head look-at, ear sway, future SpringBone).
        // Run after all controllers so they cleanly override the affected
        // bones; stage order ensures physics-secondary interceptors see the
        // look-at result.
        for (BoneInterceptor.Stage stage : BoneInterceptor.Stage.VALUES) {
            List<BoneInterceptor> stageList = interceptors.get(stage);
            if (stageList == null) continue;
            for (BoneInterceptor interceptor : stageList) {
                interceptor.apply(model, state, molangCtx, poseBuf);
            }
        }

        poseStack.pushPose();
        // Bedrock entity rendering — see ModelBaker for the X-mirror reasoning.
        rotBuf.identity().rotationY((float) Math.toRadians(180.0f - state.bodyRot));
        poseStack.last().rotate(rotBuf);
        poseStack.scale(PIXEL_SCALE, PIXEL_SCALE, PIXEL_SCALE);

        RenderType type = RenderTypes.entityCutout(state.texture);
        int packedLight = state.lightCoords;
        int packedOverlay = net.minecraft.client.renderer.entity.LivingEntityRenderer.getOverlayCoords(state, 0.0f);
        boolean[] hiddenBones = state.hiddenBones;
        collector.submitCustomGeometry(poseStack, type, (drawPose, vc) -> {
            mesh.render(model, drawPose, vc, packedLight, packedOverlay, poseBuf, hiddenBones);
        });

        if (!renderLayers.isEmpty()) {
            RenderLayerContext layerCtx = new RenderLayerContext(
                    model, poseBuf, state, poseStack, collector, packedLight, packedOverlay);
            for (RenderLayer layer : renderLayers) {
                layer.submit(layerCtx);
            }
        }

        poseStack.popPose();
    }

    /**
     * Sample one controller's contribution into the pose buffer. Three paths:
     *
     * <ol>
     *   <li><b>Crossfade</b> (within-controller switch, e.g. main: idle→sit) —
     *       sample {@code previous} and {@code current} into temp buffers
     *       (each starting from the existing pose buffer so unanimated bones
     *       stay put) and lerp them back into the shared buffer.</li>
     *   <li><b>Stop-fade</b> (controller going silent, e.g. action: harvest
     *       finished) — sample {@code current} into the buffer normally, then
     *       lerp it <em>back toward what was in the buffer before</em> using
     *       the fade alpha. As alpha goes 0→1 the controller's contribution
     *       gradually disappears, blending smoothly to whatever earlier
     *       controllers wrote.</li>
     *   <li><b>Normal</b> — single sample call, fast path.</li>
     * </ol>
     *
     * <p>Both fade paths work for OVERRIDE and ADDITIVE alike — for ADDITIVE
     * the "fully applied" pose is what's in the buffer after sampling, and
     * lerping toward the pre-existing buffer naturally fades the additive
     * delta back to zero.
     */
    private void sampleController(ControllerSnapshot snapshot, long nowNs,
                                  MolangContext ctx, float[] poseBuf, int boneCount) {
        if (snapshot == null || snapshot.isIdle()) return;

        if (snapshot.hasCrossfade()) {
            crossfade(snapshot, nowNs, ctx, poseBuf, boneCount);
            return;
        }
        if (snapshot.isFadingOut()) {
            stopFade(snapshot, nowNs, ctx, poseBuf, boneCount);
            return;
        }
        PoseSampler.sample(snapshot.current(), snapshot.blendMode(), nowNs, ctx, poseBuf);
    }

    /**
     * Within-controller crossfade. Both channels are sampled on top of the
     * existing pose buffer (so bones neither animation touches keep their
     * earlier-controller values), then lerped per
     * {@link PoseMixer#fadeAlpha smoothstep alpha}.
     */
    private void crossfade(ControllerSnapshot snapshot, long nowNs,
                           MolangContext ctx, float[] poseBuf, int boneCount) {
        int len = boneCount * PoseSampler.FLOATS_PER_BONE;
        float[] fromPose = new float[len];
        float[] toPose   = new float[len];
        System.arraycopy(poseBuf, 0, fromPose, 0, len);
        System.arraycopy(poseBuf, 0, toPose,   0, len);

        BlendMode mode = snapshot.blendMode();
        PoseSampler.sample(snapshot.previous(), mode, nowNs, ctx, fromPose);
        PoseSampler.sample(snapshot.current(),  mode, nowNs, ctx, toPose);
        float alpha = PoseMixer.fadeAlpha(snapshot.fadeStartNs(), snapshot.fadeDurationSec(), nowNs);
        PoseMixer.blend(fromPose, toPose, alpha, poseBuf, boneCount);
    }

    /**
     * Stop-fade: the controller is going silent, but its contribution lerps
     * toward the buffer's pre-sample state (= whatever earlier controllers
     * wrote) over {@code fadeDurationSec}.
     *
     * <p>Implementation: snapshot the buffer, apply the controller's full
     * contribution, then lerp from "with contribution" toward "without"
     * as alpha goes 0→1. At alpha=0 the controller still fully contributes;
     * at alpha=1 it's effectively absent.
     */
    private void stopFade(ControllerSnapshot snapshot, long nowNs,
                          MolangContext ctx, float[] poseBuf, int boneCount) {
        int len = boneCount * PoseSampler.FLOATS_PER_BONE;
        float[] existing = new float[len];
        System.arraycopy(poseBuf, 0, existing, 0, len);

        PoseSampler.sample(snapshot.current(), snapshot.blendMode(), nowNs, ctx, poseBuf);

        float alpha = PoseMixer.fadeAlpha(snapshot.fadeStartNs(), snapshot.fadeDurationSec(), nowNs);
        // lerp(poseBuf, existing, alpha) → poseBuf
        // alpha=0: full contribution remains; alpha=1: fully reverts to existing.
        PoseMixer.blend(poseBuf, existing, alpha, poseBuf, boneCount);
    }
}
