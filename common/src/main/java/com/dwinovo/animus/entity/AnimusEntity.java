package com.dwinovo.animus.entity;

import com.dwinovo.animus.anim.api.AnimusAnimated;
import com.dwinovo.animus.anim.runtime.Animator;
import com.dwinovo.animus.entity.interact.AnimusInteractHandler;
import com.dwinovo.animus.network.payload.TaskResultPayload;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.task.TaskQueue;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.tasks.AttackTargetTaskGoal;
import com.dwinovo.animus.task.tasks.MineBlockTaskGoal;
import com.dwinovo.animus.task.tasks.MoveToTaskGoal;
import com.dwinovo.animus.task.tasks.PathfindAndMineTaskGoal;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;

import java.util.List;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Single LLM-driven entity at the heart of the mod. Behaviour is driven
 * by an external LLM through the tool / task pipeline; see
 * {@link #registerGoals()} for the wiring of task-executor Goals and the
 * vanilla {@code MeleeAttackGoal} that backs the {@code attack_target} tool.
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
     * Lazy server-side task queue. Constructed on first access, which
     * happens only on a {@link ServerLevel} (the queue is populated by
     * {@link com.dwinovo.animus.network.payload.ExecuteToolPayload} on the
     * server and drained by the matching {@code LlmTaskGoal}).
     *
     * <p>The LLM-side agent loop now lives on the **client**
     * ({@link com.dwinovo.animus.client.agent.ClientAgentLoop}) so each
     * player's API key drives their own Animus. The server stays a pure
     * task executor and result router — no LLM client here.
     */
    private TaskQueue taskQueue;

    public AnimusEntity(EntityType<? extends AnimusEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return TamableAnimal.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                // Roughly zombie-equivalent. Without this the entity swings
                // through targets for 0 damage and combat tasks never resolve.
                .add(Attributes.ATTACK_DAMAGE, 4.0);
    }

    /**
     * Wires the entity's AI:
     * <ul>
     *   <li>Vanilla {@link MeleeAttackGoal} (priority 0) — permanent. Watches
     *       {@code getTarget()} and handles path-find + swing + doHurtTarget
     *       + cooldown autonomously. The {@code attack_target} tool engages
     *       it indirectly by setting the target via
     *       {@link AttackTargetTaskGoal}.</li>
     *   <li>Per-tool {@code LlmTaskGoal} subclasses (priority 0) — each
     *       atomic world-action tool is paired with one of these. They peek
     *       a single FIFO {@code TaskQueue} so only the goal matching the
     *       head record activates, enforcing serial execution; see
     *       {@link com.dwinovo.animus.task.LlmTaskGoal} javadoc.</li>
     * </ul>
     * Equal priority across the LlmTaskGoals neutralises vanilla preemption.
     * {@code MeleeAttackGoal} owns MOVE+LOOK flags while {@code LlmTaskGoal}
     * declares no flags, so the attack goal and the sentinel
     * {@code AttackTargetTaskGoal} happily run side-by-side.
     *
     * <p>Called by the {@code Mob} constructor before {@code AnimusEntity}'s
     * field initialisers have run. Don't access instance fields here — the
     * Goal subclass should only store the entity reference and read state
     * lazily from {@code canUse()} onward.
     */
    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new MeleeAttackGoal(this, 1.2D, true));
        this.goalSelector.addGoal(0, new MoveToTaskGoal(this));
        this.goalSelector.addGoal(0, new AttackTargetTaskGoal(this));
        this.goalSelector.addGoal(0, new MineBlockTaskGoal(this));
        this.goalSelector.addGoal(0, new PathfindAndMineTaskGoal(this));
    }

    /** Server-side task queue. Lazily created on first access. */
    public TaskQueue getTaskQueue() {
        if (taskQueue == null) taskQueue = new TaskQueue();
        return taskQueue;
    }

    /**
     * Per-tick server hook. After vanilla goals have ticked (and produced
     * completed task results into the queue's outbox), drain those results
     * and ship them back to the owning player's client so its
     * {@link com.dwinovo.animus.client.agent.ClientAgentLoop} can feed
     * them into the LLM conversation and trigger the next turn.
     */
    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        drainTaskResultsToOwner();
    }

    /**
     * Pull completed task records out of the queue's outbox and dispatch
     * each as a {@link TaskResultPayload} to the owning player.
     *
     * <p>If the owner is offline (signed out, different dimension and not
     * tracking, etc.), the results are dropped — silently. This matches the
     * "Animus is owner-driven" contract: there's nobody to drive the next
     * LLM turn anyway. Future hardening can buffer results and replay on
     * next login.
     */
    private void drainTaskResultsToOwner() {
        if (taskQueue == null) return;
        List<TaskRecord> completed = taskQueue.drainCompleted();
        if (completed.isEmpty()) return;
        if (!(this.getOwner() instanceof ServerPlayer owner)) {
            com.dwinovo.animus.Constants.LOG.debug(
                    "[animus-entity#{}] dropping {} task result(s) — owner offline / not a ServerPlayer",
                    this.getId(), completed.size());
            return;
        }
        com.dwinovo.animus.Constants.LOG.debug(
                "[animus-entity#{}] dispatching {} task result(s) to owner {}",
                this.getId(), completed.size(), owner.getName().getString());
        for (TaskRecord rec : completed) {
            TaskResult result = rec.getResult();
            String json = result == null
                    ? "{\"success\":false,\"message\":\"no result produced\"}"
                    : result.toJson();
            Services.NETWORK.sendToPlayer(owner, new TaskResultPayload(
                    this.getId(), rec.getToolCallId(), json));
        }
    }

    /**
     * Vanilla hook fired on death / unload / chunk-unload. Cancels every
     * pending task so its tool_call_id doesn't leak (it would otherwise
     * sit in the client's pending set forever). The cancellation results
     * are NOT shipped back — by the time {@code remove()} fires the owner
     * may already have disconnected or the entity may be in an unload-
     * before-tick state.
     */
    @Override
    public void remove(Entity.RemovalReason reason) {
        if (taskQueue != null) {
            taskQueue.cancelAll("entity removed: " + reason);
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
