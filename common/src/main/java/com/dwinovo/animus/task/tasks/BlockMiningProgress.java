package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Reusable mining-progress state machine. Encapsulates the per-tick swing /
 * particles / sound / crack-overlay loop that vanilla {@code Player} runs
 * during a hold-to-break dig, plus the destroy-on-completion call.
 *
 * <p>Lives in the {@code task.tasks} package so {@link MineBlockTaskGoal}
 * (atomic in-reach dig) and {@link PathfindAndMineTaskGoal} (walk-then-dig)
 * can share it without duplication. Static {@link #checkMineable} lets the
 * caller pre-validate before kicking off pathing.
 *
 * <h2>Timing formula</h2>
 * Mirrors {@code Player.getDestroyProgress} approximately, minus player-only
 * modifiers (haste, mining fatigue, in-air, underwater):
 * <pre>
 *   ticks = ceil(hardness * (canHarvest ? 30 : 100) / toolSpeed)
 * </pre>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #tryStart} — snapshot {@link BlockState}, compute total
 *       ticks. Returns {@code false} if the block became invalid between
 *       {@link #checkMineable} and start.</li>
 *   <li>{@link #tick} — call each tick while mining. Returns
 *       {@link Outcome} indicating progress, completion, or failure.</li>
 *   <li>{@link #cleanup} — call exactly once on terminal state to clear
 *       the crack overlay.</li>
 * </ol>
 */
public final class BlockMiningProgress {

    /** Vanilla {@code block_interaction_range} for players is 4.5. */
    public static final double REACH_SQR = 4.5 * 4.5;

    private static final int SWING_INTERVAL_TICKS = 5;

    public enum Outcome {
        IN_PROGRESS,
        /** Total ticks reached, block destroyed and dropped. */
        COMPLETED,
        /** Entity walked / was knocked out of reach mid-dig. */
        FAILED_OUT_OF_REACH,
        /** Block at pos is now a different non-air state (neighbour break, water flow). */
        FAILED_BLOCK_CHANGED,
        /** Block became air (someone else broke it) — caller may treat as success. */
        FAILED_BLOCK_GONE
    }

    private final AnimusEntity entity;
    private BlockState startState;
    private int totalTicksNeeded;
    private int progressTicks;
    private int lastStage;
    private boolean active;

    public BlockMiningProgress(AnimusEntity entity) {
        this.entity = entity;
    }

    /**
     * Cheap precondition check — does this position hold a block that can
     * be mined at all? Returns {@code null} if so, otherwise a human-readable
     * reason string (suitable for inclusion in a {@code TaskResult.fail}).
     *
     * <p>Does <strong>not</strong> check reach distance — that's the caller's
     * concern (atomic mine fails on out-of-reach, pathfind-then-mine walks
     * to range first).
     */
    public static String checkMineable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return "block is air";
        if (state.getDestroySpeed(level, pos) < 0) return "block is unbreakable";
        return null;
    }

    /**
     * Snapshot starting state and compute the dig budget. Returns
     * {@code false} if the block became invalid between the caller's
     * {@link #checkMineable} and now (race / world edit).
     */
    public boolean tryStart(BlockPos pos) {
        Level level = entity.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) return false;

        ItemStack tool = entity.getMainHandItem();
        boolean correct = tool.isCorrectToolForDrops(state);
        float toolSpeed = tool.getDestroySpeed(state);
        if (toolSpeed <= 0.0F) toolSpeed = 1.0F;
        float divisor = correct ? 30.0F : 100.0F;
        this.totalTicksNeeded = hardness == 0.0F
                ? 1
                : Math.max(1, (int) Math.ceil(hardness * divisor / toolSpeed));

        this.startState = state;
        this.progressTicks = 0;
        this.lastStage = -1;
        this.active = true;
        entity.getLookControl().setLookAt(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return true;
    }

    /** Advance one tick of mining. */
    public Outcome tick(BlockPos pos) {
        Level level = entity.level();
        Vec3 center = Vec3.atCenterOf(pos);

        if (entity.distanceToSqr(center) > REACH_SQR) {
            return Outcome.FAILED_OUT_OF_REACH;
        }
        BlockState now = level.getBlockState(pos);
        if (!now.equals(startState)) {
            return now.isAir() ? Outcome.FAILED_BLOCK_GONE : Outcome.FAILED_BLOCK_CHANGED;
        }

        entity.getLookControl().setLookAt(center.x, center.y, center.z);
        if (progressTicks % SWING_INTERVAL_TICKS == 0) {
            entity.swing(InteractionHand.MAIN_HAND);
            playHitFeedback(level, pos, now, center);
        }
        progressTicks++;

        int stage = (int) Math.floor(((float) progressTicks / totalTicksNeeded) * 10.0F);
        if (stage > 9) stage = 9;
        if (stage != lastStage) {
            level.destroyBlockProgress(entity.getId(), pos, stage);
            lastStage = stage;
        }

        if (progressTicks >= totalTicksNeeded) {
            level.destroyBlock(pos, true, entity);
            return Outcome.COMPLETED;
        }
        return Outcome.IN_PROGRESS;
    }

    /** Clear the crack overlay. Safe to call multiple times. */
    public void cleanup(BlockPos pos) {
        if (active) {
            entity.level().destroyBlockProgress(entity.getId(), pos, -1);
            active = false;
        }
    }

    public int progressTicks() { return progressTicks; }
    public int totalTicksNeeded() { return totalTicksNeeded; }
    public BlockState startState() { return startState; }

    @SuppressWarnings("deprecation")  // BlockStateBase.getSoundType() is "deprecated for
                                     // override" (Mojang convention), not phased out.
    private void playHitFeedback(Level level, BlockPos pos, BlockState state, Vec3 center) {
        SoundType soundType = state.getSoundType();
        level.playSound(null, pos, soundType.getHitSound(), SoundSource.BLOCKS,
                soundType.getVolume() * 0.25F, soundType.getPitch() * 0.5F);
        if (level instanceof ServerLevel sl) {
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                    center.x, center.y, center.z,
                    3,         // count
                    0.15, 0.15, 0.15,  // spread
                    0.0);      // speed
        }
    }
}
