package com.dwinovo.animus.entity;

import com.dwinovo.animus.pathing.exec.PathTally;
import com.dwinovo.animus.task.TaskQueue;
import com.dwinovo.animus.task.TaskRecord;
import com.dwinovo.animus.task.TaskState;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

/**
 * The companion body: a server-side fake {@link ServerPlayer}. Replaces the old
 * custom {@code AnimusEntity} Mob so the companion is a first-class player —
 * native interaction/combat code paths (universal mod compatibility), its own
 * player inventory, and free chunk loading + playerdata persistence by virtue of
 * being a list-resident player.
 *
 * <h2>Identity &amp; ownership</h2>
 * Created by {@link CompanionFactory} with a stable per-companion UUID (carried
 * in the {@link GameProfile}); the enumerable index lives in
 * {@link CompanionRegistry}. Unlike the Mob, a fake player cannot carry custom
 * {@code SynchedEntityData}, so the owner is a plain server-side field persisted
 * to the companion's own playerdata {@code .dat} via
 * {@link #addAdditionalSaveData}. Owner checks are UUID comparisons — never
 * vanilla {@code isOwnedBy} (which resolves through a level and breaks across
 * dimensions).
 */
public final class AnimusPlayer extends ServerPlayer {

    private static final String NBT_KEY_OWNER = "AnimusOwner";

    /** Owner's player UUID. Null only transiently before the first assignment. */
    private UUID ownerUuid;

    // ---- task hosting (lifted from the old AnimusEntity Mob) ----
    private TaskQueue taskQueue;
    private final PathTally pathTally = new PathTally();
    private TaskRecord activeTask;
    private String debugTask;

    public AnimusPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
                        ClientInformation clientInformation) {
        super(server, level, profile, clientInformation);
    }

    /** The loaded companion body with this UUID, or {@code null} if not spawned. */
    public static AnimusPlayer findByUuid(MinecraftServer server, UUID uuid) {
        return server.getPlayerList().getPlayer(uuid) instanceof AnimusPlayer ap ? ap : null;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    /** Cross-dimension safe owner check — UUID comparison, not level-scoped lookup. */
    public boolean isOwnedByPlayer(UUID playerUuid) {
        return ownerUuid != null && ownerUuid.equals(playerUuid);
    }

    /** The owner as an online player, server-wide; null when offline. */
    public ServerPlayer resolveOwnerPlayer() {
        return ownerUuid == null ? null : level().getServer().getPlayerList().getPlayer(ownerUuid);
    }

    // ---- task hosting ----

    public TaskQueue getTaskQueue() {
        if (taskQueue == null) taskQueue = new TaskQueue();
        return taskQueue;
    }

    /** The record currently executing (polled out of the queue), for owner-interrupt. */
    public TaskRecord getActiveTask() {
        return activeTask;
    }

    public void setActiveTask(TaskRecord record) {
        this.activeTask = record;
    }

    /** Cancel queued records AND the running one (its state flips off RUNNING). */
    public void cancelAllTasks(String reason) {
        getTaskQueue().cancelAll(reason);
        if (activeTask != null && activeTask.getState() == TaskState.RUNNING) {
            activeTask.setState(TaskState.CANCELLED);
        }
    }

    public PathTally pathTally() {
        return pathTally;
    }

    public void setDebugTask(String description) {
        this.debugTask = description;
    }

    public String getDebugTask() {
        return debugTask;
    }

    /** True if {@code item} sits anywhere in the inventory (hotbar/main/offhand all count). */
    public boolean ensureInInventory(Item item) {
        var inv = getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) return true;
        }
        return false;
    }

    // ---- server tick (Carpet's EntityPlayerMPFake trick) ----

    /**
     * Drive the body's own movement physics. A real {@link ServerPlayer} runs
     * {@code travel} (against {@code zza}/{@code xxa}), food, air and pose inside
     * {@link #doTick()}, which the network layer invokes via
     * {@code connection.tick()}. A fake player's connection is a no-op, so
     * {@code doTick()} never fires and the body would only ever turn (a direct
     * {@code setYRot} write) without walking. The entity system already calls
     * {@code super.tick()} (menus / container / position sync), so we add the
     * missing {@code doTick()} movement pass here — exactly as Carpet's
     * {@code EntityPlayerMPFake.tick()} does. Every 10 ticks we resync the
     * connection position and let chunk loading follow the body so it never
     * walks out of its loaded area.
     */
    @Override
    public void tick() {
        if (level() instanceof ServerLevel sl && sl.getGameTime() % 10 == 0) {
            this.connection.resetPosition();
            sl.getChunkSource().move(this);
        }
        super.tick();
        try {
            this.doTick();
        } catch (Exception ignored) {
            // mirrors Carpet — fake-connection internals can NPE on edge cases
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (ownerUuid != null) {
            output.store(NBT_KEY_OWNER, UUIDUtil.CODEC, ownerUuid);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read(NBT_KEY_OWNER, UUIDUtil.CODEC).ifPresent(uuid -> this.ownerUuid = uuid);
    }
}
