package com.dwinovo.animus.entity;

import com.dwinovo.animus.anim.api.AnimusAnimated;
import com.dwinovo.animus.anim.runtime.Animator;
import com.dwinovo.animus.entity.interact.AnimusInteractHandler;
import com.dwinovo.animus.network.payload.TaskResultPayload;
import com.dwinovo.animus.pathing.exec.PathTally;
import com.dwinovo.animus.platform.Services;
import com.dwinovo.animus.task.TaskQueue;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import com.dwinovo.animus.task.tasks.CheckFurnaceTaskGoal;
import com.dwinovo.animus.task.tasks.CollectFurnaceTaskGoal;
import com.dwinovo.animus.task.tasks.CraftTaskGoal;
import com.dwinovo.animus.task.tasks.DepositItemsTaskGoal;
import com.dwinovo.animus.task.tasks.DropItemsTaskGoal;
import com.dwinovo.animus.task.tasks.EquipTaskGoal;
import com.dwinovo.animus.task.tasks.TakeItemsTaskGoal;
import com.dwinovo.animus.task.tasks.WaitTaskGoal;
import com.dwinovo.animus.task.tasks.HuntTaskGoal;
import com.dwinovo.animus.task.tasks.LocateStructureTaskGoal;
import com.dwinovo.animus.task.tasks.ShootTaskGoal;
import com.dwinovo.animus.task.tasks.CollectItemsTaskGoal;
import com.dwinovo.animus.task.tasks.LoadFurnaceTaskGoal;
import com.dwinovo.animus.task.tasks.MineBlockTaskGoal;
import com.dwinovo.animus.task.tasks.MoveToTaskGoal;
import com.dwinovo.animus.task.tasks.PlaceBlockTaskGoal;
import com.dwinovo.animus.task.tasks.UseItemTaskGoal;
import com.dwinovo.animus.task.tasks.EatItemTaskGoal;
import net.minecraft.core.Vec3i;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;

/**
 * Single LLM-driven companion entity. A tamed Animus is a self-contained
 * pet: it carries its own inventory, acts on the world through the tool /
 * task pipeline, and chats with its owner through a per-entity GUI.
 *
 * <h2>Tame → own → command</h2>
 * Spawned wild; the player tames it by feeding {@code animus:tame_foods}
 * (see {@link AnimusInteractHandler}). Once tamed, an owner main-hand
 * right-click opens its GUI (chat / inventory / model). The owner's chat
 * drives a client-side LLM loop ({@code EntityAgentLoop}) whose tool calls
 * are executed on this body server-side.
 *
 * <h2>Why PathfinderMob + self-managed ownership (not TamableAnimal)</h2>
 * The TamableAnimal chain (← Animal ← AgeableMob) buys two small methods and
 * charges for them with breeding/love/age baggage we had to actively suppress
 * and — worse — level-scoped owner resolution ({@code getOwner()} only finds
 * an owner in the PET's dimension), a trap for a mod whose pets work across
 * dimensions. Ownership here is one synched {@link EntityReference} field;
 * all owner queries are UUID-based ({@link #isOwnedByPlayer}) or server-wide
 * ({@link #resolveOwnerPlayer}). {@link net.minecraft.world.entity.OwnableEntity}
 * is still implemented so third-party pet-protection mods recognise us.
 *
 * <h2>Inventory</h2>
 * Each Animus owns a {@value #INVENTORY_SIZE}-slot {@link SimpleContainer}.
 * Mined / vacuumed drops land here; the owner moves items in and out via the
 * GUI's chest menu. Persisted in entity NBT. Contents drop on death. The LLM
 * reads it server-side through the {@code get_self_status} query.
 *
 * <h2>Synced model key</h2>
 * {@link #DATA_MODEL_KEY} holds the render-model {@link Identifier}; changed
 * via the GUI's model picker → {@code SetModelPayload}, broadcast by vanilla
 * {@link SynchedEntityData}.
 */
public class AnimusEntity extends PathfinderMob implements OwnableEntity, AnimusAnimated {

    public static final int INVENTORY_SIZE = 27;

    /**
     * How long after the last owner heartbeat the loop still counts as "live"
     * for dimension-follow. The heartbeat arrives every
     * {@code InitTicketType.TASK_TICKET_REFRESH_TICKS} ticks while the owner is
     * mid-conversation; a few periods of grace means a dropped packet doesn't
     * flip a conversing pet into following the owner through a portal.
     */
    private static final int OWNER_HEARTBEAT_GRACE_TICKS = 100;

    /**
     * Owner reference — THE tame state (null = wild). Synched so the client
     * can gate the GUI and show ownership without a server round-trip;
     * persisted in {@link #addAdditionalSaveData}. Self-managed instead of
     * inheriting TamableAnimal: every read goes through UUID-based helpers
     * that work across dimensions.
     */
    private static final EntityDataAccessor<java.util.Optional<EntityReference<LivingEntity>>> DATA_OWNER =
            SynchedEntityData.defineId(AnimusEntity.class, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE);

    private static final EntityDataAccessor<String> DATA_MODEL_KEY =
            SynchedEntityData.defineId(AnimusEntity.class, EntityDataSerializers.STRING);

    /**
     * Human-readable description of what this Animus is doing right now (e.g.
     * {@code "move_to 12,64,-30"} or {@code "idle"}). Server writes it as tasks
     * start/stop (see {@link com.dwinovo.animus.task.LlmTaskGoal}); synced to
     * all tracking clients for the {@code /animus debug} head overlay.
     */
    private static final EntityDataAccessor<String> DATA_DEBUG_TASK =
            SynchedEntityData.defineId(AnimusEntity.class, EntityDataSerializers.STRING);

    /** Default debug-task string: nothing running. */
    private static final String DEBUG_TASK_IDLE = "idle";

    private static final String NBT_KEY_MODEL = "ModelKey";
    private static final String NBT_KEY_INVENTORY = "Inventory";
    private static final String NBT_KEY_OWNER = "Owner";

    private Animator animator;

    private Identifier cachedModelKey = AnimusAnimated.DEFAULT_MODEL_KEY;
    private String cachedModelKeyString = AnimusAnimated.DEFAULT_MODEL_KEY.toString();

    /** This Animus's own inventory. Server-authoritative; backs the GUI chest
     *  menu and the {@code get_self_status} query. */
    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);

    /**
     * Lazy server-side task queue, populated by {@code ExecuteToolPayload}
     * and drained by the matching {@code LlmTaskGoal}. The LLM loop itself
     * runs on the owner's client; the server is a pure executor + router.
     */
    private TaskQueue taskQueue;

    /**
     * Per-task tally of terrain the pathfinder modified while travelling (dug
     * obstructions / placed scaffolding). Reset at each task start and folded
     * into the task result by {@link com.dwinovo.animus.task.LlmTaskGoal} so the
     * model sees what navigation cost. See {@link PathTally}.
     */
    private final PathTally pathTally = new PathTally();

    public AnimusEntity(EntityType<? extends AnimusEntity> type, Level level) {
        super(type, level);
        this.setCanPickUpLoot(true);
        // Vanilla navigation may start/route through water (the WaterEscapeGoal
        // relies on it to swim ashore, exactly like vanilla wolves do).
        this.getNavigation().setCanFloat(true);
    }

    /**
     * Actually swimming — in water deeper than the fluid-jump threshold, feet
     * off the ground. The water reflexes key off this, and the task executors
     * yield while it's true (their ground-based movement can't make progress
     * in open water; the escape reflex gets the body ashore, then they replan).
     */
    public boolean isDeepInWater() {
        return this.isInWater()
                && this.getFluidHeight(net.minecraft.tags.FluidTags.WATER) > this.getFluidJumpThreshold();
    }

    /**
     * Resolve an Animus by UUID across ALL dimensions. A working companion may
     * be in another dimension or far outside its owner's view, kept ticking by
     * its own chunk tickets — payload handlers must never assume co-location.
     */
    public static AnimusEntity findByUuid(net.minecraft.server.MinecraftServer server,
                                          java.util.UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(uuid) instanceof AnimusEntity animus) {
                return animus;
            }
        }
        return null;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 4.0);
    }

    // ---- ownership & taming (self-managed, cross-dimension safe) ----

    @Override
    public EntityReference<LivingEntity> getOwnerReference() {
        return this.entityData.get(DATA_OWNER).orElse(null);
    }

    /** Tamed = has an owner. There is no separate tame flag. */
    public boolean isTame() {
        return getOwnerReference() != null;
    }

    /** Bind this Animus to {@code player}. Particles are the caller's job. */
    public void tame(Player player) {
        this.entityData.set(DATA_OWNER, java.util.Optional.of(EntityReference.of(player)));
    }

    /** Heart (7) / smoke (6) taming feedback, broadcast by the interact handler. */
    @Override
    public void handleEntityEvent(byte id) {
        if (id == 7) {
            spawnTamingParticles(true);
        } else if (id == 6) {
            spawnTamingParticles(false);
        } else {
            super.handleEntityEvent(id);
        }
    }

    private void spawnTamingParticles(boolean success) {
        var particle = success
                ? net.minecraft.core.particles.ParticleTypes.HEART
                : net.minecraft.core.particles.ParticleTypes.SMOKE;
        for (int i = 0; i < 7; i++) {
            this.level().addParticle(particle,
                    this.getRandomX(1.0), this.getRandomY() + 0.5, this.getRandomZ(1.0),
                    this.random.nextGaussian() * 0.02,
                    this.random.nextGaussian() * 0.02,
                    this.random.nextGaussian() * 0.02);
        }
    }

    /**
     * Never despawn. Animal gave this for free; PathfinderMob does not — a
     * wild (or worse, tamed-but-far) Animus must not be culled by distance.
     */
    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        // Water reflexes first: FloatGoal (vanilla, JUMP-channel only) bobs the
        // body to the surface so it never drowns; WaterEscapeGoal swims it to
        // the nearest shore. Land mobs sink like stones without these.
        this.goalSelector.addGoal(0, new net.minecraft.world.entity.ai.goal.FloatGoal(this));
        this.goalSelector.addGoal(1, new com.dwinovo.animus.entity.ai.WaterEscapeGoal(this));
        this.goalSelector.addGoal(0, new MeleeAttackGoal(this, 1.2D, true));
        this.goalSelector.addGoal(0, new MoveToTaskGoal(this));
        this.goalSelector.addGoal(0, new HuntTaskGoal(this));
        this.goalSelector.addGoal(0, new ShootTaskGoal(this));
        this.goalSelector.addGoal(0, new LocateStructureTaskGoal(this));
        this.goalSelector.addGoal(0, new com.dwinovo.animus.task.tasks.LocateBiomeTaskGoal(this));
        this.goalSelector.addGoal(0, new CollectItemsTaskGoal(this));
        this.goalSelector.addGoal(0, new MineBlockTaskGoal(this));
        this.goalSelector.addGoal(0, new CraftTaskGoal(this));
        this.goalSelector.addGoal(0, new EquipTaskGoal(this));
        this.goalSelector.addGoal(0, new LoadFurnaceTaskGoal(this));
        this.goalSelector.addGoal(0, new CheckFurnaceTaskGoal(this));
        this.goalSelector.addGoal(0, new CollectFurnaceTaskGoal(this));
        this.goalSelector.addGoal(0, new PlaceBlockTaskGoal(this));
        this.goalSelector.addGoal(0, new com.dwinovo.animus.task.tasks.BreakBlockTaskGoal(this));
        this.goalSelector.addGoal(0, new UseItemTaskGoal(this));
        this.goalSelector.addGoal(0, new EatItemTaskGoal(this));
        this.goalSelector.addGoal(0, new WaitTaskGoal(this));
        this.goalSelector.addGoal(0, new DropItemsTaskGoal(this));
        this.goalSelector.addGoal(0, new DepositItemsTaskGoal(this));
        this.goalSelector.addGoal(0, new TakeItemsTaskGoal(this));
    }

    // ---- inventory ----

    /** This Animus's own inventory. Server-authoritative; backs the GUI chest menu. */
    public SimpleContainer getInventory() {
        return inventory;
    }

    /**
     * Make sure at least one {@code item} sits in the backpack container,
     * pulling the stack back from a HAND slot when that's where it lives.
     * Equipping moves items out of the container into equipment slots, which
     * made them invisible to every "find it in the inventory" code path —
     * the classic symptom: get_self_status shows a bucket in the main hand
     * while use_item insists there is "no bucket in inventory".
     *
     * @return true if the item is now present in the container
     */
    public boolean ensureInInventory(net.minecraft.world.item.Item item) {
        if (inventory.countItem(item) > 0) return true;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
            ItemStack held = this.getItemBySlot(slot);
            if (held.isEmpty() || held.getItem() != item) continue;
            ItemStack overflow = inventory.addItem(held.copy());
            if (overflow.getCount() == held.getCount()) return false;   // backpack full
            this.setItemSlot(slot, overflow);   // whatever didn't fit stays in hand
            inventory.setChanged();
            return true;
        }
        return false;
    }

    // ---- task queue ----

    /**
     * The record currently being executed by an {@link com.dwinovo.animus.task.LlmTaskGoal}
     * — it has been polled OUT of the queue, so {@link TaskQueue#cancelAll}
     * cannot reach it. Tracked here so an owner interrupt can cancel the
     * running body action too, not just the queued ones.
     */
    private TaskRecord activeTask;

    public TaskQueue getTaskQueue() {
        if (taskQueue == null) taskQueue = new TaskQueue();
        return taskQueue;
    }

    /** Called by {@code LlmTaskGoal} on start (the record) and stop (null). */
    public void setActiveTask(TaskRecord record) {
        this.activeTask = record;
    }

    /**
     * Cancel everything: queued records AND the one currently running. The
     * running goal sees its state flip off RUNNING, so the GoalSelector stops
     * it next tick — {@code stop()} builds a CANCELLED result and halts the
     * body (navigator, mining overlay) through the goal's own teardown.
     */
    public void cancelAllTasks(String reason) {
        getTaskQueue().cancelAll(reason);
        if (activeTask != null && activeTask.getState() == TaskState.RUNNING) {
            activeTask.setState(TaskState.CANCELLED);
        }
    }

    /** Per-task pathfinder terrain tally (dug / placed while travelling). */
    public PathTally pathTally() {
        return pathTally;
    }

    /** The single sanctioned writer of this body's locomotion controls. */
    private final com.dwinovo.animus.pathing.exec.BodyMotor motor =
            new com.dwinovo.animus.pathing.exec.BodyMotor(this);

    public com.dwinovo.animus.pathing.exec.BodyMotor motor() {
        return motor;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        refreshChunkTicket(level);
        if (level.getGameTime() % 40 == 0) AnimusLastSeen.update(this);
        drainTaskResultsToOwner();
    }

    /**
     * Game time of the last owner-liveness heartbeat ({@code KeepLoadedPayload}).
     * The owner's agent loop, which runs on the owner's client, beats while it
     * is mid-turn; the server reads recency through {@link #isOwnerLoopLive()}.
     * Transient (not saved): a fresh body starts cold and the next heartbeat
     * re-warms it within a second.
     */
    private long lastOwnerHeartbeatTime = Long.MIN_VALUE;

    /**
     * Cross-dimension owner identity check. Vanilla {@code isOwnedBy} resolves
     * the owner through THIS entity's level ({@code EntityReference} lookup),
     * so it silently returns false whenever owner and pet are in different
     * dimensions — every payload handler must use this UUID comparison instead.
     */
    public boolean isOwnedByPlayer(java.util.UUID playerUuid) {
        var ref = this.getOwnerReference();
        return ref != null && ref.getUUID().equals(playerUuid);
    }

    /**
     * The owner as an online player, resolved server-wide — works regardless
     * of which dimension either side is in. Null when the owner is offline.
     */
    public ServerPlayer resolveOwnerPlayer() {
        var ref = this.getOwnerReference();
        if (ref == null || !(this.level() instanceof ServerLevel sl)) return null;
        return sl.getServer().getPlayerList().getPlayer(ref.getUUID());
    }

    /** Stamp owner liveness; called by the {@code KeepLoadedPayload} heartbeat handler. */
    public void markOwnerHeartbeat() {
        if (this.level() instanceof ServerLevel sl) {
            lastOwnerHeartbeatTime = sl.getGameTime();
        }
    }

    /**
     * Is a world task currently executing or queued? Two callers: reflex goals
     * that would fight a task's own body control (water escape vs a SWIM path),
     * and the chunk-ticket floor in {@link #refreshChunkTicket} (a body mid-task
     * keeps itself loaded even if a heartbeat packet is dropped).
     */
    public boolean hasTaskWork() {
        return (activeTask != null && activeTask.getState() == TaskState.RUNNING)
                || (taskQueue != null && taskQueue.pendingCount() > 0);
    }

    /**
     * Has the owner's agent loop heartbeat arrived recently — i.e. is the owner
     * mid-conversation with this pet right now? Dimension-follow reads this to
     * leave a conversing-but-idle pet behind when the owner takes a portal
     * (it's reachable cross-dimension via tickets), while a fully idle pet
     * follows along.
     */
    public boolean isOwnerLoopLive() {
        return this.level() instanceof ServerLevel sl
                && sl.getGameTime() - lastOwnerHeartbeatTime <= OWNER_HEARTBEAT_GRACE_TICKS;
    }

    /**
     * Server-side floor of the owner-liveness chunk lease: while a task is
     * running or queued, keep re-issuing the self-loading ticket so an in-flight
     * job survives even if a client heartbeat is momentarily lost. The owner
     * heartbeat ({@code KeepLoadedPayload}) is the primary lease holder during
     * think-time gaps; this covers the body's own work. The ticket's timeout
     * handles every teardown case (task done, loop idle, death, crash) by simply
     * expiring once nothing refreshes it.
     */
    private void refreshChunkTicket(ServerLevel level) {
        if (!hasTaskWork()) return;
        if (level.getGameTime() % com.dwinovo.animus.init.InitTicketType.TASK_TICKET_REFRESH_TICKS != 0) return;
        level.getChunkSource().addTicketWithRadius(
                com.dwinovo.animus.init.InitTicketType.TASK, this.chunkPosition(),
                com.dwinovo.animus.init.InitTicketType.TASK_TICKET_RADIUS);
    }

    private void drainTaskResultsToOwner() {
        if (taskQueue == null) return;
        List<TaskRecord> completed = taskQueue.drainCompleted();
        if (completed.isEmpty()) return;
        // Server-wide owner resolution: the owner may be in another dimension
        // while this pet works (vanilla getOwner() would return null there and
        // every result of remote work would be silently dropped).
        ServerPlayer owner = resolveOwnerPlayer();
        if (owner == null) {
            com.dwinovo.animus.Constants.LOG.debug(
                    "[animus-entity#{}] dropping {} task result(s) — owner offline",
                    this.getId(), completed.size());
            return;
        }
        for (TaskRecord rec : completed) {
            TaskResult result = rec.getResult();
            String json = result == null
                    ? "{\"success\":false,\"message\":\"no result produced\"}"
                    : result.toJson();
            Services.NETWORK.sendToPlayer(owner, new TaskResultPayload(
                    this.getUUID(), rec.getToolCallId(), json));
        }
    }

    // ---- item pickup → own inventory ----

    @Override
    protected Vec3i getPickupReach() {
        return new Vec3i(2, 1, 2);
    }

    /** Only tamed Animus vacuum items, and only when there's room. */
    @Override
    public boolean wantsToPickUp(ServerLevel level, ItemStack stack) {
        return this.isTame() && this.inventory.canAddItem(stack);
    }

    /** Route picked-up stacks into this Animus's own inventory. */
    @Override
    protected void pickUpItem(ServerLevel level, ItemEntity itemEntity) {
        ItemStack ground = itemEntity.getItem();
        if (ground.isEmpty()) return;
        int before = ground.getCount();
        ItemStack leftover = inventory.addItem(ground.copy());
        int absorbed = before - leftover.getCount();
        if (absorbed <= 0) return;
        this.onItemPickup(itemEntity);
        this.take(itemEntity, absorbed);
        ItemStack remaining = itemEntity.getItem();
        remaining.shrink(absorbed);
        if (remaining.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setItem(remaining);
        }
    }

    // ---- lifecycle ----

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (taskQueue != null) {
            // Cancel in-flight tasks, then flush the resulting CANCELLED records
            // to the owner immediately. customServerAiStep won't run again after
            // removal, so without this synchronous drain those results would
            // never reach the client and the agent loop would wait forever
            // (there is no client-side stale watchdog anymore).
            // A dimension change is a CLONE, not an end: teach the model what
            // happened so it re-orients and carries on in the new body.
            cancelAllTasks(reason == Entity.RemovalReason.CHANGED_DIMENSION
                    ? "you crossed into another dimension — call get_self_status to "
                            + "confirm where you are, then continue your plan"
                    : "entity removed: " + reason);
            drainTaskResultsToOwner();
        }
        if (reason.shouldDestroy() && this.level() instanceof ServerLevel sl) {
            // Real death (KILLED/DISCARDED) — NOT a dimension change
            // (CHANGED_DIMENSION.shouldDestroy() is false). Drop the revival
            // index entry: a dead pet must not be chunk-ticket-revivable.
            AnimusLastSeen.remove(sl.getServer(), this.getUUID());
            // Tell the owner's
            // client so its agent loop hard-stops: a dead body can't act, so it
            // must never call the LLM again. Without this the loop keeps
            // dispatching tools into the void and the LLM flails on the
            // resulting "entity not found" errors. Server-wide owner resolution:
            // a pet dying far away must still notify a cross-dimension owner.
            ServerPlayer owner = resolveOwnerPlayer();
            if (owner != null) {
                Services.NETWORK.sendToPlayer(owner,
                        new com.dwinovo.animus.network.payload.AnimusDeathPayload(this.getUUID()));
            }
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack s = inventory.getItem(i);
                if (!s.isEmpty()) this.spawnAtLocation(sl, s);
            }
            inventory.clearContent();
            // Equipment lives outside the SimpleContainer. equip_item can move a
            // crafted tool/weapon/armor into these slots; vanilla's probabilistic
            // mob-equipment drop would usually lose it, so drop them all here too
            // to keep the "pet's gear returns on death" invariant.
            for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                ItemStack worn = this.getItemBySlot(slot);
                if (!worn.isEmpty()) {
                    this.spawnAtLocation(sl, worn);
                    this.setItemSlot(slot, ItemStack.EMPTY);
                }
            }
        }
        super.remove(reason);
    }

    /**
     * Cross-dimension travel CLONES the entity (same UUID, new instance), and
     * the clone starts with cold transient state — no heartbeat stamp, no chunk
     * ticket. Vanilla's PORTAL ticket covers the destination for only 15s; hand
     * the clone a warm ticket so it survives the gap until the owner's next
     * heartbeat re-targets it in the new dimension (the loop is UUID-keyed and
     * resolves to the clone without noticing the trip). The last-seen index is
     * rewritten on the clone's tick, so a heartbeat racing the move still aims
     * at the right dimension within ~2s.
     */
    @Override
    public Entity teleport(net.minecraft.world.level.portal.TeleportTransition transition) {
        Entity result = super.teleport(transition);
        if (result instanceof AnimusEntity fresh && fresh != this
                && fresh.level() instanceof ServerLevel sl) {
            sl.getChunkSource().addTicketWithRadius(
                    com.dwinovo.animus.init.InitTicketType.TASK, fresh.chunkPosition(),
                    com.dwinovo.animus.init.InitTicketType.TASK_TICKET_RADIUS);
        }
        return result;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER, java.util.Optional.empty());
        builder.define(DATA_MODEL_KEY, AnimusAnimated.DEFAULT_MODEL_KEY.toString());
        builder.define(DATA_DEBUG_TASK, DEBUG_TASK_IDLE);
    }

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
        }
        return cachedModelKey;
    }

    public void setModelKey(Identifier id) {
        this.entityData.set(DATA_MODEL_KEY, id.toString());
    }

    @Override
    public String getCurrentTask() {
        if (taskQueue != null && taskQueue.hasPending()) {
            return "busy:" + taskQueue.pendingCount();
        }
        return "none";
    }

    /**
     * Set the running-task description shown by the {@code /animus debug}
     * overlay. Called from {@link com.dwinovo.animus.task.LlmTaskGoal} on the
     * server tick thread; {@link SynchedEntityData} broadcasts it to clients.
     * Passing {@code null}/blank resets to {@link #DEBUG_TASK_IDLE}.
     */
    public void setDebugTask(String description) {
        String value = (description == null || description.isBlank())
                ? DEBUG_TASK_IDLE : description;
        if (!value.equals(this.entityData.get(DATA_DEBUG_TASK))) {
            this.entityData.set(DATA_DEBUG_TASK, value);
        }
    }

    /** Current running-task description for the debug overlay. Never null. */
    public String getDebugTask() {
        return this.entityData.get(DATA_DEBUG_TASK);
    }

    // ---- interaction ----

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        InteractionResult result = AnimusInteractHandler.handle(this, player, hand);
        if (result != InteractionResult.PASS) {
            return result;
        }
        return super.mobInteract(player, hand);
    }

    // ---- persistence ----

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        EntityReference.store(getOwnerReference(), output, NBT_KEY_OWNER);
        output.putString(NBT_KEY_MODEL, this.entityData.get(DATA_MODEL_KEY));
        List<ItemStack> items = new ArrayList<>(inventory.getContainerSize());
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            items.add(inventory.getItem(i));
        }
        output.store(NBT_KEY_INVENTORY, ItemStack.OPTIONAL_CODEC.listOf(), items);
    }

    @Override
    public void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.entityData.set(DATA_OWNER, java.util.Optional.ofNullable(
                EntityReference.<LivingEntity>read(input, NBT_KEY_OWNER)));
        this.entityData.set(DATA_MODEL_KEY,
                input.getStringOr(NBT_KEY_MODEL, AnimusAnimated.DEFAULT_MODEL_KEY.toString()));
        input.read(NBT_KEY_INVENTORY, ItemStack.OPTIONAL_CODEC.listOf()).ifPresent(list -> {
            for (int i = 0; i < list.size() && i < inventory.getContainerSize(); i++) {
                inventory.setItem(i, list.get(i));
            }
        });
    }
}
