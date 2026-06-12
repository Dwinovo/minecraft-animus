package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.platform.Services;
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
