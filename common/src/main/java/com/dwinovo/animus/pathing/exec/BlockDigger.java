package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.entity.AnimusPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Progressive block breaking that drives the SAME native server entry point a
 * real client's packets hit — a faithful port of Carpet's
 * {@code EntityPlayerActionPack} ATTACK-on-block. A fake player has no client to
 * run the mining loop, so we BE the client:
 * <ul>
 *   <li>begin: {@code handleBlockBreakAction(START_DESTROY_BLOCK)} + the block's
 *       left-click {@code attack} (note blocks, redstone-ore glow, …); creative
 *       breaks instantly on START; an insta-mineable block breaks on START too;</li>
 *   <li>each tick: accumulate the block's real {@link BlockState#getDestroyProgress}
 *       and broadcast the crack overlay (breaker id {@code -1}, like Carpet — the
 *       server does NOT self-complete a survival break for a fake player);</li>
 *   <li>finish: {@code handleBlockBreakAction(STOP_DESTROY_BLOCK)} → the SERVER
 *       destroys the block (drops / durability / events). We do NOT clear the
 *       crack — the block vanishing removes it, so there's no "intact for one
 *       frame" flicker — and we set a {@code blockHitDelay} so the next dig waits
 *       for the destroy to land instead of re-starting the same block;</li>
 *   <li>interrupted: {@code ABORT_DESTROY_BLOCK} + clear the crack.</li>
 * </ul>
 * Shared by path-obstruction clearing ({@code PlayerPathExecutor}), auto-mine
 * ({@code MineCompanionTask}), and {@link Interaction} (break_block / interact).
 */
public final class BlockDigger {

    /** Carpet broadcasts the crack under breaker id -1 (not the player's entity id),
     *  so the server's own per-player crack clearing on STOP can't wipe it early. */
    private static final int CRACK_ID = -1;
    /** Ticks to wait after a break before starting another (Carpet blockHitDelay). */
    private static final int BLOCK_HIT_DELAY = 5;

    private final AnimusPlayer player;
    private BlockPos pos;
    private float progress;       // accumulated 0..1 (Carpet curBlockDamageMP)
    private boolean started;      // START_DESTROY_BLOCK has been sent for `pos`
    private int blockHitDelay;    // post-break cooldown (survives reset())

    public BlockDigger(AnimusPlayer player) {
        this.player = player;
    }

    /** The block currently being dug, or {@code null} when idle. */
    public BlockPos current() {
        return pos;
    }

    /**
     * Advance the dig of {@code target} by one tick (restarting cleanly if the
     * target changed): face it, drive the native break action, swing.
     *
     * @return {@code true} on the tick the block's break is committed (STOP sent).
     */
    public boolean dig(BlockPos target) {
        Level level = player.level();
        if (blockHitDelay > 0) {                    // let the previous break land first
            blockHitDelay--;
            InputDriver.halt(player);
            return false;
        }
        if (pos == null || !pos.equals(target)) {
            start(target);
        }
        InputDriver.halt(player);
        // Aim the crosshair at a raycast-VERIFIED point on the block (Baritone
        // RotationUtils.reachable) and break the face the ray actually hits — like a real
        // player. If nothing on the block is in line of sight, don't break this tick
        // (Baritone: no reachable rotation → no CLICK_LEFT); hold for a clear angle.
        BlockHitResult hit = reachableHit(pos);
        if (hit == null) {
            return false;
        }
        InputDriver.lookAt(player, hit.getLocation());
        Direction side = hit.getDirection();
        BlockState state = level.getBlockState(pos);

        if (!started) {
            started = true;
            player.gameMode.handleBlockBreakAction(pos,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, level.getMaxY(), -1);
            player.swing(InteractionHand.MAIN_HAND);
            if (player.getAbilities().instabuild) {
                blockHitDelay = BLOCK_HIT_DELAY;
                reset();
                return true;                         // creative: START broke it
            }
            if (!state.isAir()) {
                state.attack(level, pos, player);    // left-click punch
                if (state.getDestroyProgress(player, level, pos) >= 1.0f) {
                    reset();                         // instamine: START broke it (no STOP — Carpet)
                    return true;
                }
            }
            return false;                            // begin accumulating next tick
        }

        // Survival: accumulate the real per-tick destroy fraction; broadcast the crack.
        progress += state.getDestroyProgress(player, level, pos);
        int stage = Math.min(9, (int) (progress * 10.0f));
        level.destroyBlockProgress(CRACK_ID, pos, stage);
        player.swing(InteractionHand.MAIN_HAND);
        if (progress >= 1.0f) {
            // STOP → server destroys. Do NOT clear the crack: the block vanishing
            // removes it (no intact-for-a-frame flicker). Carpet-exact.
            player.gameMode.handleBlockBreakAction(pos,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, side, level.getMaxY(), -1);
            blockHitDelay = BLOCK_HIT_DELAY;
            reset();
            return true;
        }
        return false;
    }

    private void start(BlockPos target) {
        cancel();
        pos = target.immutable();
        progress = 0.0f;
        started = false;
        // Hold the best tool BEFORE timing the dig — getDestroyProgress reads the held
        // item, and the pathing cost model prices every break with the best hotbar tool
        // (Baritone switchToBestToolFor).
        switchToBestTool(player.level().getBlockState(pos));
    }

    /** Hold the item that mines {@code state} fastest in the main hand. Scans the WHOLE
     *  inventory (not just the hotbar) and swaps a backpack tool into the hand via
     *  {@link AnimusPlayer#holdInHand} — a deliberate divergence from Baritone's hotbar-only
     *  ToolSet, kept consistent with the cost model (NavContext.scanBestTool) so the planned
     *  break cost still matches the tool actually used. */
    private void switchToBestTool(BlockState state) {
        Inventory inv = player.getInventory();
        int best = inv.getSelectedSlot();
        float bestSpeed = inv.getItem(best).getDestroySpeed(state);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            float s = inv.getItem(i).getDestroySpeed(state);
            if (s > bestSpeed) {
                bestSpeed = s;
                best = i;
            }
        }
        player.holdInHand(best);
    }

    /** Abandon an IN-PROGRESS dig: ABORT it server-side and clear the crack (Carpet
     *  inactiveTick). A completed break never comes through here — its crack is left
     *  for the block-break to remove. */
    public void cancel() {
        if (pos != null) {
            if (started) {
                player.gameMode.handleBlockBreakAction(pos,
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                        Direction.DOWN, player.level().getMaxY(), -1);
            }
            player.level().destroyBlockProgress(CRACK_ID, pos, -1);   // clear the crack
        }
        reset();
    }

    /** Clear dig state. Deliberately does NOT touch {@link #blockHitDelay} (a
     *  post-break cooldown that must outlive the break) or the crack overlay. */
    private void reset() {
        pos = null;
        progress = 0.0f;
        started = false;
    }

    /**
     * Baritone {@code RotationUtils.reachable}: the first point ON {@code pos} the eye can
     * actually raycast to — the block's shape centre first, then its six face centres. The
     * returned {@link BlockHitResult} carries the exact aim point ({@code getLocation}) AND
     * the face the ray hits ({@code getDirection}), so the dig looks at the real interaction
     * face like a player would. {@code null} if nothing on the block is in line of sight.
     */
    private BlockHitResult reachableHit(BlockPos pos) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange();
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        if (shape.isEmpty()) {
            shape = Shapes.block();
        }
        // Shape centre, then Baritone's BLOCK_SIDE_MULTIPLIERS (the six face centres).
        Vec3[] aims = {
                offsetOn(pos, shape, 0.5, 0.5, 0.5),
                offsetOn(pos, shape, 0.5, 0.0, 0.5),
                offsetOn(pos, shape, 0.5, 1.0, 0.5),
                offsetOn(pos, shape, 0.5, 0.5, 0.0),
                offsetOn(pos, shape, 0.5, 0.5, 1.0),
                offsetOn(pos, shape, 0.0, 0.5, 0.5),
                offsetOn(pos, shape, 1.0, 0.5, 0.5),
        };
        for (Vec3 aim : aims) {
            Vec3 dir = aim.subtract(eye);
            if (dir.lengthSqr() < 1.0e-8) continue;
            Vec3 end = eye.add(dir.normalize().scale(reach));
            BlockHitResult res = level.clip(new ClipContext(
                    eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (res.getType() == HitResult.Type.BLOCK && res.getBlockPos().equals(pos)) {
                return res;
            }
        }
        return null;
    }

    /** A point on the block's shape per Baritone's offset formula:
     *  {@code min*m + max*(1-m)} on each axis. */
    private static Vec3 offsetOn(BlockPos pos, VoxelShape shape, double mx, double my, double mz) {
        double x = shape.min(Direction.Axis.X) * mx + shape.max(Direction.Axis.X) * (1 - mx);
        double y = shape.min(Direction.Axis.Y) * my + shape.max(Direction.Axis.Y) * (1 - my);
        double z = shape.min(Direction.Axis.Z) * mz + shape.max(Direction.Axis.Z) * (1 - mz);
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

}
