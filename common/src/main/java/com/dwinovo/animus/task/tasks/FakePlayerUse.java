package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Performs a real vanilla right-click item interaction on behalf of an Animus,
 * by lending the held item to the loader's shared fake player (via
 * {@link com.dwinovo.animus.platform.services.IFakePlayerBridge}) and driving
 * {@code gameMode.useItem*}. This routes flint&steel / ender eye / bucket /
 * bonemeal / placement etc. through the exact code a player would hit.
 *
 * <h2>Inventory reconciliation</h2>
 * The fake player is given a copy of the entity's whole stack from {@code invSlot}.
 * After the interaction, whatever the fake player ends up holding/gaining (the
 * stack minus what was consumed, plus any returns like an emptied bucket) is
 * scooped back into the entity's inventory; the original slot is cleared first.
 * The fake player's state is wiped by the bridge's {@code withFakePlayer}
 * wrapper, so nothing leaks.
 *
 * <p>Self-affecting uses (eating, drinking potions) are intentionally NOT routed
 * here — those would apply to the fake player, not the Animus. This is for
 * <em>world-affecting</em> uses only.
 */
public final class FakePlayerUse {

    private FakePlayerUse() {}

    /**
     * Use the stack in {@code invSlot} against a block face — gaze first, like
     * a player. Returns the vanilla result, or {@code FAIL} when the target
     * face isn't visible from here (no clicking through walls).
     *
     * <p>Order matters: the fake player is positioned, AIMED at the hit point,
     * then a real {@code level.clip} verifies the eye actually sees the
     * clicked face. Only then does {@code useItemOn} run; if it PASSes without
     * effect, the air-use fallback fires with the gaze already on target —
     * items like {@code BucketItem} implement {@code use()} with their OWN
     * eye-ray pick instead of {@code useOn}, so the aim is what makes them
     * work (the old "bucket on source water had no effect" bug was exactly an
     * unaimed fallback).
     */
    public static InteractionResult useOnBlock(AnimusEntity entity, int invSlot, BlockHitResult hit) {
        ServerLevel level = (ServerLevel) entity.level();
        return Services.FAKE_PLAYER.withFakePlayer(level, fp -> {
            position(fp, entity);
            aimAt(fp, hit.getLocation());
            if (!canSee(fp, level, hit)) {
                return InteractionResult.FAIL;   // occluded — a player couldn't click this
            }
            ItemStack copy = entity.getInventory().getItem(invSlot).copy();
            fp.setItemInHand(InteractionHand.MAIN_HAND, copy);
            InteractionResult res = fp.gameMode.useItemOn(
                    fp, level, fp.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
            if (!res.consumesAction()) {
                res = fp.gameMode.useItem(
                        fp, level, fp.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
            }
            reconcile(entity, invSlot, fp);
            return res;
        });
    }

    /**
     * Does the aimed fake player's eye actually see the intended hit point?
     * Real raycast (OUTLINE, ignoring fluids) pulled back an epsilon so the
     * clicked face's own plane doesn't self-occlude. MISS = reached the
     * point; a BLOCK hit must be the intended block to count.
     */
    private static boolean canSee(ServerPlayer fp, ServerLevel level, BlockHitResult intended) {
        Vec3 eye = fp.getEyePosition();
        Vec3 to = intended.getLocation();
        Vec3 dir = to.subtract(eye);
        double len = dir.length();
        if (len < 1.0e-4) return true;
        Vec3 end = eye.add(dir.scale((len - 0.05) / len));
        BlockHitResult clip = level.clip(new net.minecraft.world.level.ClipContext(
                eye, end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, fp));
        return clip.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                || clip.getBlockPos().equals(intended.getBlockPos());
    }

    /** Use the stack in {@code invSlot} in the air (throwables, etc.). Returns the vanilla result. */
    public static InteractionResult useInAir(AnimusEntity entity, int invSlot) {
        ServerLevel level = (ServerLevel) entity.level();
        return Services.FAKE_PLAYER.withFakePlayer(level, fp -> {
            position(fp, entity);
            ItemStack copy = entity.getInventory().getItem(invSlot).copy();
            fp.setItemInHand(InteractionHand.MAIN_HAND, copy);
            InteractionResult res = fp.gameMode.useItem(
                    fp, level, fp.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
            reconcile(entity, invSlot, fp);
            return res;
        });
    }

    // ---- HOLD (press-and-keep-pressed) uses: bow-likes that charge ----

    /**
     * Begin a held use: lease the fake player across ticks, aim, lend the
     * stack (plus matching projectiles for bow-likes — releaseUsing pulls
     * ammo from the user's inventory) and press right-click. Returns the
     * leased player while the item is genuinely "in use", else cleans up and
     * returns null (the item has no hold behaviour — caller falls back to a
     * click). EVERY exit path of the caller must end in {@link #finishHold}
     * or {@link #abortHold}.
     */
    public static ServerPlayer beginHold(AnimusEntity entity, int invSlot, Vec3 aimOrNull) {
        ServerLevel level = (ServerLevel) entity.level();
        ServerPlayer fp = Services.FAKE_PLAYER.acquireLease(level);
        position(fp, entity);
        if (aimOrNull != null) {
            aimAt(fp, aimOrNull);
        }
        ItemStack copy = entity.getInventory().getItem(invSlot).copy();
        fp.setItemInHand(InteractionHand.MAIN_HAND, copy);
        lendProjectiles(entity, fp, copy);
        fp.gameMode.useItem(fp, level, fp.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
        if (!fp.isUsingItem()) {
            reconcile(entity, invSlot, fp);
            Services.FAKE_PLAYER.releaseLease(fp);
            return null;
        }
        return fp;
    }

    /** Release after {@code heldTicks} of charge (fires the bow), reconcile, return the lease. */
    public static void finishHold(AnimusEntity entity, int invSlot, ServerPlayer fp, int heldTicks) {
        ServerLevel level = (ServerLevel) entity.level();
        ItemStack using = fp.getUseItem();
        if (!using.isEmpty()) {
            int remaining = Math.max(0, using.getUseDuration(fp) - heldTicks);
            using.releaseUsing(level, fp, remaining);
        }
        fp.stopUsingItem();
        reconcile(entity, invSlot, fp);
        Services.FAKE_PLAYER.releaseLease(fp);
    }

    /** Cancel a hold without releasing the use (task interrupted/timed out). */
    public static void abortHold(AnimusEntity entity, int invSlot, ServerPlayer fp) {
        fp.stopUsingItem();
        reconcile(entity, invSlot, fp);
        Services.FAKE_PLAYER.releaseLease(fp);
    }

    /** Bow-likes consume ammo from the USER's inventory — lend it along. */
    private static void lendProjectiles(AnimusEntity entity, ServerPlayer fp, ItemStack weapon) {
        if (!(weapon.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem w)) return;
        SimpleContainer inv = entity.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && w.getSupportedHeldProjectiles().test(s)) {
                fp.getInventory().add(s.copy());
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        inv.setChanged();
    }

    /** Outcome of a pathfinder scaffold placement. */
    public enum PlaceResult {
        /** Block is in the world; one item was consumed from the slot. */
        PLACED,
        /** No clickable neighbour face exists — the plan's support vanished. */
        NO_SUPPORT,
        /** Vanilla refused (entity overlap, protection, …) — retry next tick. */
        REFUSED
    }

    /**
     * Place one scaffold block into {@code cell} the way a bridging player
     * does: pick a solid neighbour, sneak (so clicking a chest/furnace
     * support places instead of opening its GUI), aim at the shared face and
     * {@code useItemOn}. Vanilla's own placement rules apply — most
     * importantly its entity-collision rejection, which retires the
     * hand-rolled inflated-AABB obstruction glue: a placement that would
     * wedge a body simply REFUSES, and the drive retries next tick.
     *
     * <p>No line-of-sight gate, deliberately: scaffolding is point-blank work
     * against the block at the feet, mirroring the mining engine's stance on
     * clearance digging.
     *
     * <p>Consumption is accounted manually (exactly one item on success) —
     * dupe-proof regardless of the fake player's game mode.
     */
    public static PlaceResult placeScaffold(AnimusEntity entity, int invSlot, BlockPos cell) {
        ServerLevel level = (ServerLevel) entity.level();
        BlockHitResult support = findSupportClick(level, cell, entity);
        if (support == null) {
            return PlaceResult.NO_SUPPORT;
        }
        return Services.FAKE_PLAYER.withFakePlayer(level, fp -> {
            position(fp, entity);
            aimAt(fp, support.getLocation());
            fp.setShiftKeyDown(true);
            try {
                ItemStack original = entity.getInventory().getItem(invSlot);
                fp.setItemInHand(InteractionHand.MAIN_HAND, original.copy());
                fp.gameMode.useItemOn(fp, level,
                        fp.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, support);
                // Judge by the WORLD, not the result code: did the cell fill?
                boolean placed = !level.getBlockState(cell).getCollisionShape(level, cell).isEmpty();
                if (placed) {
                    original.shrink(1);
                    entity.getInventory().setChanged();
                    return PlaceResult.PLACED;
                }
                return PlaceResult.REFUSED;
            } finally {
                fp.setShiftKeyDown(false);
            }
        });
    }

    /**
     * A clickable neighbour face that places into {@code cell}: below first
     * (pillar tops), then horizontals nearest the entity (bridging clicks the
     * block behind), then above.
     */
    private static BlockHitResult findSupportClick(ServerLevel level, BlockPos cell, AnimusEntity entity) {
        java.util.List<net.minecraft.core.Direction> order = new java.util.ArrayList<>(6);
        order.add(net.minecraft.core.Direction.DOWN);
        java.util.List<net.minecraft.core.Direction> horizontals = new java.util.ArrayList<>(4);
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            horizontals.add(d);
        }
        horizontals.sort(java.util.Comparator.comparingDouble(d ->
                entity.distanceToSqr(Vec3.atCenterOf(cell.relative(d)))));
        order.addAll(horizontals);
        order.add(net.minecraft.core.Direction.UP);

        for (net.minecraft.core.Direction d : order) {
            BlockPos neighbour = cell.relative(d);
            if (level.getBlockState(neighbour).getCollisionShape(level, neighbour).isEmpty()) {
                continue;
            }
            net.minecraft.core.Direction face = d.getOpposite();   // neighbour.relative(face) == cell
            Vec3 hit = Vec3.atCenterOf(neighbour)
                    .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            return new BlockHitResult(hit, face, neighbour, false);
        }
        return null;
    }

    /** Stand the fake player where the Animus is, facing the same way (placement orientation). */
    private static void position(ServerPlayer fp, AnimusEntity entity) {
        fp.setPos(entity.getX(), entity.getY(), entity.getZ());
        fp.setYRot(entity.getYRot());
        fp.setXRot(entity.getXRot());
        fp.setYHeadRot(entity.getYRot());
    }

    /** Point the fake player's eyes at {@code target} so eye-ray items (buckets) hit it. */
    private static void aimAt(ServerPlayer fp, Vec3 target) {
        Vec3 eye = fp.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        fp.setYRot(yaw);
        fp.setYHeadRot(yaw);
        fp.setXRot(pitch);
    }

    /**
     * Move the consumption/returns from the fake player back to the entity: clear
     * the original slot, then add back everything the fake player now holds.
     */
    private static void reconcile(AnimusEntity entity, int invSlot, ServerPlayer fp) {
        SimpleContainer inv = entity.getInventory();
        inv.setItem(invSlot, ItemStack.EMPTY);
        Inventory fpInv = fp.getInventory();
        for (int i = 0; i < fpInv.getContainerSize(); i++) {
            ItemStack s = fpInv.getItem(i);
            if (s.isEmpty()) continue;
            ItemStack leftover = inv.addItem(s.copy());
            if (!leftover.isEmpty() && entity.level() instanceof ServerLevel sl) {
                entity.spawnAtLocation(sl, leftover);
            }
        }
        inv.setChanged();
    }
}
