package com.dwinovo.animus.entity;

import com.dwinovo.animus.agent.loop.AgentLoop;
import com.dwinovo.animus.anim.api.AnimusAnimated;
import com.dwinovo.animus.anim.runtime.Animator;
import com.dwinovo.animus.entity.interact.AnimusInteractHandler;
import com.dwinovo.animus.task.TaskQueue;
import com.dwinovo.animus.task.tasks.MoveToTaskGoal;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Single LLM-driven entity at the heart of the mod. The Brain / ToolCall /
 * LLM client integration arrives in later plans; for now {@link
 * #registerGoals()} stays empty and the entity drifts in place.
 *
 * <h2>Why TamableAnimal</h2>
 * Vanilla {@code TamableAnimal} gives us, free of code:
 * <ul>
 *   <li>{@code OwnerUUID} NBT persistence + {@link #getOwner()} resolution</li>
 *   <li>{@link #isTame()} / {@link #tame(Player)} / {@link #isOwnedBy} helpers</li>
 *   <li>Heart-particle (event 7) and smoke-puff (event 6) on the client when
 *       {@code level.broadcastEntityEvent} is called with those codes</li>
 *   <li>The owner concept is the same one the LLM layer will read as "whose
 *       commands to trust" — wiring owner first means the chat / tool-call
 *       layer just hooks {@code getOwnerUUID()} when it lands</li>
 * </ul>
 * Breeding (inherited from {@link Animal}) is explicitly disabled —
 * Animus is intended to be unique per spawn, not a renewable resource.
 *
 * <h2>Synced model key</h2>
 * {@link #DATA_MODEL_KEY} is a string holding the {@link Identifier} of the
 * baked model to render this entity with — by default
 * {@link AnimusAnimated#DEFAULT_MODEL_KEY}. Players change it through the
 * model-chooser GUI (sneak-right-click as the owner) which dispatches a
 * {@code SetModelPayload} to the server; the server sets this field and
 * vanilla {@link SynchedEntityData} broadcasts the change to every tracking
 * client.
 *
 * <h2>Persistence</h2>
 * The model key survives world reload via the {@code "ModelKey"} NBT entry.
 * Owner UUID and {@code isTame} are handled by {@code TamableAnimal}'s own
 * {@code addAdditionalSaveData} / {@code readAdditionalSaveData}, called
 * automatically via {@code super}.
 */
public class AnimusEntity extends TamableAnimal implements AnimusAnimated {

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

    /**
     * Lazy server-side state. Both are constructed on first access, which
     * happens from {@code customServerAiStep} or a {@code Goal.canUse} call
     * — i.e. only on a {@link ServerLevel}. The client never touches them,
     * so the lazy null-guard pattern keeps the client JVM clean even though
     * the fields are declared in {@code common}.
     */
    private TaskQueue taskQueue;
    private AgentLoop agentLoop;

    public AnimusEntity(EntityType<? extends AnimusEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return TamableAnimal.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    /**
     * Registers every LLM-driven task as a {@code Goal} at priority 0.
     * Equal priority neutralises vanilla's preemption logic — tasks coexist
     * by {@link Goal.Flag channel} but never interrupt each other. See
     * {@link com.dwinovo.animus.task.LlmTaskGoal} for the lifecycle bridge.
     *
     * <p>Called by the {@code Mob} constructor before {@code AnimusEntity}'s
     * field initialisers have run. Don't access instance fields here — the
     * Goal subclass should only store the entity reference and read state
     * lazily from {@code canUse()} onward.
     */
    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new MoveToTaskGoal(this));
    }

    /** Server-side task queue. Lazily created on first access. */
    public TaskQueue getTaskQueue() {
        if (taskQueue == null) taskQueue = new TaskQueue();
        return taskQueue;
    }

    /** Server-side agent loop. Lazily created on first access. */
    public AgentLoop getAgentLoop() {
        if (agentLoop == null) agentLoop = new AgentLoop(this);
        return agentLoop;
    }

    /**
     * Per-tick server hook. Vanilla calls this after {@code goalSelector.tick}
     * and before {@code moveControl} — a clean place to drain completed
     * tasks into the agent loop and trigger the next LLM turn if needed.
     */
    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        getAgentLoop().tick();
    }

    /**
     * Vanilla hook fired on death / unload / chunk-unload. Flushes pending
     * tasks so the agent loop doesn't leave dangling tool_call_ids waiting
     * forever, and disables any further LLM activity. Idempotent: removing
     * an entity twice doesn't double-shutdown because the loop's internal
     * flag stays set.
     */
    @Override
    public void remove(Entity.RemovalReason reason) {
        if (agentLoop != null) {
            agentLoop.shutdown();
        }
        super.remove(reason);
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
        // Surface the queue head's tool name for client-side display hooks
        // (rendering bubble, debug overlay). Server-side only — the field is
        // never populated client-side because TaskQueue is server-lazy.
        if (taskQueue != null && taskQueue.hasPending()) {
            return "busy:" + taskQueue.pendingCount();
        }
        return "none";
    }

    /**
     * Right-click dispatch. {@link AnimusInteractHandler} centralises the
     * state table (untamed/tamed × owner/not × food/sneak); anything it
     * doesn't recognise returns {@link InteractionResult#PASS} so vanilla
     * fall-through (e.g. leashing) still works.
     */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        InteractionResult result = AnimusInteractHandler.handle(this, player, hand);
        if (result != InteractionResult.PASS) {
            return result;
        }
        return super.mobInteract(player, hand);
    }

    /**
     * Vanilla {@link Animal#isFood} drives breeding/baby-growth and the
     * "food in hand follows me" Goal. Returning {@code false} unconditionally
     * disables both — Animus must not breed (no second-generation entities
     * with copied owner UUIDs) and its food affinity is handled exclusively
     * by {@link AnimusInteractHandler}.
     */
    @Override
    public boolean isFood(ItemStack stack) {
        return false;
    }

    /** Disabled — Animus is single-spawn-per-egg, no offspring. */
    @Override
    public boolean canMate(Animal partner) {
        return false;
    }

    /** Safety net: even if mating were somehow triggered, never produce a child. */
    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
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
