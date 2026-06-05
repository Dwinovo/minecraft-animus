package com.dwinovo.animus.entity;

import com.dwinovo.animus.anim.api.AnimusAnimated;
import com.dwinovo.animus.anim.runtime.Animator;
import com.dwinovo.animus.entity.interact.AnimusInteractHandler;
import com.dwinovo.animus.network.payload.AnimusInventoryPayload;
import com.dwinovo.animus.network.payload.TaskResultPayload;
import com.dwinovo.animus.pathing.exec.PathTally;
import com.dwinovo.animus.platform.Services;
import net.minecraft.world.food.FoodData;
import com.dwinovo.animus.task.TaskQueue;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.tasks.AttackTargetTaskGoal;
import com.dwinovo.animus.task.tasks.CheckFurnaceTaskGoal;
import com.dwinovo.animus.task.tasks.CollectFurnaceTaskGoal;
import com.dwinovo.animus.task.tasks.CraftTaskGoal;
import com.dwinovo.animus.task.tasks.EquipTaskGoal;
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
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.animal.Animal;
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
 * <h2>Why TamableAnimal</h2>
 * Vanilla gives us owner-UUID persistence, {@link #isTame()} /
 * {@link #tame(Player)} / {@link #isOwnedBy}, and the heart / smoke particle
 * events for the taming feedback — all for free.
 *
 * <h2>Inventory</h2>
 * Each Animus owns a {@value #INVENTORY_SIZE}-slot {@link SimpleContainer}.
 * Mined / vacuumed drops land here; the owner moves items in and out via the
 * GUI's chest menu. Persisted in entity NBT. Contents drop on death. A
 * snapshot is pushed to the owner ({@link AnimusInventoryPayload}) whenever
 * it changes so the client-side {@code get_storage} tool stays current.
 *
 * <h2>Synced model key</h2>
 * {@link #DATA_MODEL_KEY} holds the render-model {@link Identifier}; changed
 * via the GUI's model picker → {@code SetModelPayload}, broadcast by vanilla
 * {@link SynchedEntityData}.
 */
public class AnimusEntity extends TamableAnimal implements AnimusAnimated {

    public static final int INVENTORY_SIZE = 27;

    /** Food level (0-20) at/above which the Animus naturally regenerates health. */
    private static final int REGEN_FOOD_THRESHOLD = 18;
    /** Ticks between natural-regen heals while well-fed (~4s, vanilla slow regen). */
    private static final int REGEN_INTERVAL_TICKS = 80;
    /** Ticks between passive hunger drains (~30s) so the bar isn't static. */
    private static final int HUNGER_DRAIN_INTERVAL = 600;

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

    /**
     * Synced mirror of {@link #foodData}'s level (0-20) so the client-side
     * {@code get_self_status} tool can report it — the model needs to see how
     * fed it is to decide when to {@code eat_item}. Server writes it each tick.
     */
    private static final EntityDataAccessor<Integer> DATA_FOOD_LEVEL =
            SynchedEntityData.defineId(AnimusEntity.class, EntityDataSerializers.INT);

    /** Default debug-task string: nothing running. */
    private static final String DEBUG_TASK_IDLE = "idle";

    private static final String NBT_KEY_MODEL = "ModelKey";
    private static final String NBT_KEY_INVENTORY = "Inventory";

    private Animator animator;

    private Identifier cachedModelKey = AnimusAnimated.DEFAULT_MODEL_KEY;
    private String cachedModelKeyString = AnimusAnimated.DEFAULT_MODEL_KEY.toString();

    /**
     * This Animus's own inventory. Marks {@link #inventoryDirty} on any
     * change so {@link #customServerAiStep} can push a fresh snapshot to the
     * owner exactly once per tick instead of per-slot-mutation.
     */
    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE) {
        @Override
        public void setChanged() {
            super.setChanged();
            inventoryDirty = true;
        }
    };
    private boolean inventoryDirty = false;

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

    /**
     * Player-style hunger for the Animus. Food fills this (via {@code eat_item});
     * health regenerates only while well-fed, so eating heals over time rather
     * than instantly. Server-side + persisted; ticked in {@link #tickHunger}.
     * Vanilla {@code FoodData.tick(ServerPlayer)} is player-coupled, so we reuse
     * its state + {@code eat()} but run our own regen/drain.
     */
    private final FoodData foodData = new FoodData();
    private int regenTimer = 0;
    private int hungerTimer = 0;

    public AnimusEntity(EntityType<? extends AnimusEntity> type, Level level) {
        super(type, level);
        this.setCanPickUpLoot(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return TamableAnimal.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 4.0);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new MeleeAttackGoal(this, 1.2D, true));
        this.goalSelector.addGoal(0, new MoveToTaskGoal(this));
        this.goalSelector.addGoal(0, new AttackTargetTaskGoal(this));
        this.goalSelector.addGoal(0, new MineBlockTaskGoal(this));
        this.goalSelector.addGoal(0, new CraftTaskGoal(this));
        this.goalSelector.addGoal(0, new EquipTaskGoal(this));
        this.goalSelector.addGoal(0, new LoadFurnaceTaskGoal(this));
        this.goalSelector.addGoal(0, new CheckFurnaceTaskGoal(this));
        this.goalSelector.addGoal(0, new CollectFurnaceTaskGoal(this));
        this.goalSelector.addGoal(0, new PlaceBlockTaskGoal(this));
        this.goalSelector.addGoal(0, new UseItemTaskGoal(this));
        this.goalSelector.addGoal(0, new EatItemTaskGoal(this));
    }

    // ---- inventory ----

    /** This Animus's own inventory. Server-authoritative; backs the GUI chest menu. */
    public SimpleContainer getInventory() {
        return inventory;
    }

    /** Push the current inventory snapshot to the owning player, if online. */
    public void syncInventoryToOwner() {
        if (!(this.getOwner() instanceof ServerPlayer owner)) return;
        List<ItemStack> list = new ArrayList<>(inventory.getContainerSize());
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            list.add(inventory.getItem(i));
        }
        Services.NETWORK.sendToPlayer(owner, new AnimusInventoryPayload(this.getId(), list));
    }

    // ---- task queue ----

    public TaskQueue getTaskQueue() {
        if (taskQueue == null) taskQueue = new TaskQueue();
        return taskQueue;
    }

    /** Per-task pathfinder terrain tally (dug / placed while travelling). */
    public PathTally pathTally() {
        return pathTally;
    }

    /** Player-style hunger state. {@code eat_item} fills it; regen reads it. */
    public FoodData foodData() {
        return foodData;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        tickHunger();
        drainTaskResultsToOwner();
        if (inventoryDirty) {
            inventoryDirty = false;
            syncInventoryToOwner();
        }
    }

    /**
     * Player-style hunger tick (we run our own — {@code FoodData.tick} is
     * player-coupled). Well-fed → slow natural health regen (which costs food);
     * idle/active → slow drain so the Animus needs occasional feeding. There is
     * deliberately NO starvation damage: at empty food it simply stops
     * regenerating (a companion shouldn't die because the owner forgot to feed).
     */
    private void tickHunger() {
        // Slow drain over time so the bar isn't static (saturation first, then food).
        if (++hungerTimer >= HUNGER_DRAIN_INTERVAL) {
            hungerTimer = 0;
            if (foodData.getSaturationLevel() > 0.0f) {
                foodData.setSaturation(Math.max(0.0f, foodData.getSaturationLevel() - 1.0f));
            } else if (foodData.getFoodLevel() > 0) {
                foodData.setFoodLevel(foodData.getFoodLevel() - 1);
            }
        }
        // Natural regen while well-fed and hurt.
        if (foodData.getFoodLevel() >= REGEN_FOOD_THRESHOLD
                && getHealth() < getMaxHealth() && isAlive()) {
            if (++regenTimer >= REGEN_INTERVAL_TICKS) {
                regenTimer = 0;
                heal(1.0f);
                // Regen costs food (saturation first, then a food point).
                if (foodData.getSaturationLevel() > 0.0f) {
                    foodData.setSaturation(Math.max(0.0f, foodData.getSaturationLevel() - 1.0f));
                } else {
                    foodData.setFoodLevel(Math.max(0, foodData.getFoodLevel() - 1));
                }
            }
        } else {
            regenTimer = 0;
        }
        // Mirror to the synced accessor (no-op when unchanged) for get_self_status.
        if (this.entityData.get(DATA_FOOD_LEVEL) != foodData.getFoodLevel()) {
            this.entityData.set(DATA_FOOD_LEVEL, foodData.getFoodLevel());
        }
    }

    /** Synced food level (0-20). Readable client-side (for {@code get_self_status}). */
    public int getFoodLevel() {
        return this.entityData.get(DATA_FOOD_LEVEL);
    }

    private void drainTaskResultsToOwner() {
        if (taskQueue == null) return;
        List<TaskRecord> completed = taskQueue.drainCompleted();
        if (completed.isEmpty()) return;
        if (!(this.getOwner() instanceof ServerPlayer owner)) {
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
                    this.getId(), rec.getToolCallId(), json));
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
            taskQueue.cancelAll("entity removed: " + reason);
            drainTaskResultsToOwner();
        }
        if (reason.shouldDestroy() && this.level() instanceof ServerLevel sl) {
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

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MODEL_KEY, AnimusAnimated.DEFAULT_MODEL_KEY.toString());
        builder.define(DATA_DEBUG_TASK, DEBUG_TASK_IDLE);
        builder.define(DATA_FOOD_LEVEL, 20);
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

    /** Food affinity / breeding stay disabled — taming is handled explicitly. */
    @Override
    public boolean isFood(ItemStack stack) {
        return false;
    }

    @Override
    public boolean canMate(Animal partner) {
        return false;
    }

    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    // ---- persistence ----

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString(NBT_KEY_MODEL, this.entityData.get(DATA_MODEL_KEY));
        List<ItemStack> items = new ArrayList<>(inventory.getContainerSize());
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            items.add(inventory.getItem(i));
        }
        output.store(NBT_KEY_INVENTORY, ItemStack.OPTIONAL_CODEC.listOf(), items);
        foodData.addAdditionalSaveData(output);
    }

    @Override
    public void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.entityData.set(DATA_MODEL_KEY,
                input.getStringOr(NBT_KEY_MODEL, AnimusAnimated.DEFAULT_MODEL_KEY.toString()));
        input.read(NBT_KEY_INVENTORY, ItemStack.OPTIONAL_CODEC.listOf()).ifPresent(list -> {
            for (int i = 0; i < list.size() && i < inventory.getContainerSize(); i++) {
                inventory.setItem(i, list.get(i));
            }
        });
        foodData.readAdditionalSaveData(input);
    }
}
