package com.dwinovo.animus.entity;

import com.dwinovo.animus.anim.api.AnimusAnimated;
import com.dwinovo.animus.anim.runtime.Animator;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Single LLM-driven entity at the heart of the mod. The Brain / ToolCall /
 * LLM client integration arrives in later plans; for now {@link
 * #registerGoals()} stays empty and the entity drifts in place.
 *
 * <h2>Synced model key</h2>
 * {@link #DATA_MODEL_KEY} is a string holding the {@link Identifier} of the
 * baked model to render this entity with — by default
 * {@link AnimusAnimated#DEFAULT_MODEL_KEY}. Players change it through the
 * model-chooser GUI (right-click while sneaking) which dispatches a
 * {@code SetModelPayload} to the server; the server sets this field and
 * vanilla {@link SynchedEntityData} broadcasts the change to every tracking
 * client.
 *
 * <h2>Persistence</h2>
 * The model key survives world reload via the {@code "ModelKey"} NBT entry.
 * Animator state is recreated on first client-side render after load —
 * server stays dormant.
 */
public class AnimusEntity extends PathfinderMob implements AnimusAnimated {

    private static final EntityDataAccessor<String> DATA_MODEL_KEY =
            SynchedEntityData.defineId(AnimusEntity.class, EntityDataSerializers.STRING);

    private static final String NBT_KEY_MODEL = "ModelKey";

    private Animator animator;

    /**
     * Cache of the parsed {@link Identifier} so {@link #getModelKey()} doesn't
     * re-parse every frame. Refreshed when the underlying synced string changes.
     */
    private Identifier cachedModelKey = AnimusAnimated.DEFAULT_MODEL_KEY;
    private String cachedModelKeyString = AnimusAnimated.DEFAULT_MODEL_KEY.toString();

    public AnimusEntity(EntityType<? extends AnimusEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MODEL_KEY, AnimusAnimated.DEFAULT_MODEL_KEY.toString());
    }

    /**
     * Vanilla hook fired on every {@link SynchedEntityData} change (both
     * sides). When the model key changes, the animator's currently held
     * {@code BakedAnimation}s become stale — their bone indices belong to
     * the previous model's skeleton, so leaving them in place would
     * index-out-of-bounds against the new model's pose buffer on the next
     * frame. Reset the animator so each controller re-picks its animation
     * fresh.
     *
     * <p>{@code animator} is created lazily on the client; the null guard
     * keeps the server side (which never builds an animator) inert.
     */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key.equals(DATA_MODEL_KEY) && animator != null) {
            animator.resetAll();
        }
    }

    @Override
    public Animator getAnimator() {
        if (animator == null) animator = new Animator();
        return animator;
    }

    @Override
    public Identifier getModelKey() {
        String current = this.entityData.get(DATA_MODEL_KEY);
        if (!current.equals(cachedModelKeyString)) {
            Identifier parsed = Identifier.tryParse(current);
            if (parsed != null) {
                cachedModelKey = parsed;
                cachedModelKeyString = current;
            }
            // Bad value: keep the previously cached id so rendering still resolves.
        }
        return cachedModelKey;
    }

    /** Server-side setter; vanilla SynchedEntityData broadcasts to clients. */
    public void setModelKey(Identifier id) {
        this.entityData.set(DATA_MODEL_KEY, id.toString());
    }

    @Override
    public String getCurrentTask() {
        // Stub for Phase 1 — wired up once the Brain / ToolCall plumbing lands.
        return "none";
    }

    /**
     * Sneaking right-click opens the model-chooser GUI on the client side;
     * the server returns {@link InteractionResult#SUCCESS} so the swing
     * animation plays and no other interaction handler runs. Without the
     * sneak modifier, falls through to vanilla mob interaction.
     *
     * <p>The {@code ChooseModelScreen.open} call is guarded by the
     * client-side check — the dedicated server JVM never loads the
     * (client-only) screen class.
     */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player.isShiftKeyDown() && hand == InteractionHand.MAIN_HAND) {
            if (this.level().isClientSide()) {
                com.dwinovo.animus.client.screen.ChooseModelScreen.open(this);
            }
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString(NBT_KEY_MODEL, this.entityData.get(DATA_MODEL_KEY));
    }

    @Override
    public void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.entityData.set(DATA_MODEL_KEY,
                input.getStringOr(NBT_KEY_MODEL, AnimusAnimated.DEFAULT_MODEL_KEY.toString()));
    }
}
