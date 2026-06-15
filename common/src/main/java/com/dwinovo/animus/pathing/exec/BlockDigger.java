package com.dwinovo.animus.pathing.exec;

import com.dwinovo.animus.entity.AnimusPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

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
        InputDriver.lookAt(player, Vec3.atCenterOf(pos));
        Direction side = faceToward(player.getEyePosition(), pos);
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

    /** Select the hotbar slot whose item mines {@code state} fastest
     *  (Baritone ToolSet.getBestSlot / switchToBestToolFor). */
    private void switchToBestTool(BlockState state) {
        Inventory inv = player.getInventory();
        int best = inv.getSelectedSlot();
        float bestSpeed = inv.getItem(best).getDestroySpeed(state);
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            float s = inv.getItem(i).getDestroySpeed(state);
            if (s > bestSpeed) {
                bestSpeed = s;
                best = i;
            }
        }
        inv.setSelectedSlot(best);
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

    /** The block face nearest the eye — the side a real raycast would have hit. */
    private static Direction faceToward(Vec3 from, BlockPos pos) {
        double dx = from.x - (pos.getX() + 0.5);
        double dy = from.y - (pos.getY() + 0.5);
        double dz = from.z - (pos.getZ() + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ax >= ay && ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (az >= ax && az >= ay) return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        return dy > 0 ? Direction.UP : Direction.DOWN;
    }
}
