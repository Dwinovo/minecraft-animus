package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.Constants;
import com.dwinovo.animus.entity.AnimusEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Reusable mining-progress state machine. Encapsulates the per-tick swing /
 * particles / sound / crack-overlay loop that vanilla {@code Player} runs
 * during a hold-to-break dig, plus the destroy-on-completion call.
 *
 * <p>Lives in the {@code task.tasks} package so the intent-level
 * {@link MineBlockTaskGoal} (scan → pathfind → dig loop) and any future
 * dig site can share it without duplication. Static {@link #checkMineable}
 * lets the caller pre-validate before kicking off pathing.
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
     * Decide whether the entity's current main-hand item can <em>harvest</em>
     * {@code state} — i.e. actually yield its drops. Returns {@code null} when
     * the dig is valid (the block needs no special tool, or the held item is the
     * right tool type <em>and</em> tier). Otherwise returns a short, actionable
     * guidance string naming the minimum tool required, e.g.
     * {@code "minecraft:iron_ore can't be harvested with minecraft:wooden_pickaxe
     * — need at least a stone pickaxe"}.
     *
     * <p>This is the "is this a valid dig" gate the LLM-facing {@code mine_block}
     * tool uses to refuse fruitless mining (breaking a block for zero drops) and
     * instead tell the model what to equip — the result message is the model's
     * instruction manual. It is deliberately <strong>not</strong> applied to the
     * pathfinder's obstruction-clearing digs: clearing a route doesn't care about
     * drops, only the LLM's harvest target does.
     */
    public static String harvestRequirement(AnimusEntity entity, BlockState state) {
        // Blocks that don't require a specific tool (dirt, wood, sand, ...) always
        // drop with bare hands — mirrors Player.hasCorrectToolForDrops.
        if (!state.requiresCorrectToolForDrops()) return null;
        ItemStack tool = entity.getMainHandItem();
        if (tool.isCorrectToolForDrops(state)) return null;   // right type + tier

        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String toolType = requiredToolType(state);
        String need = toolType == null
                ? "a stronger tool"
                : "a " + requiredTier(state) + " " + toolType;
        return blockId + " can't be harvested with " + describeHeld(tool)
                + " — need at least " + need;
    }

    /** The tool type a block's loot expects, from its {@code mineable/*} tag; null if none. */
    private static String requiredToolType(BlockState s) {
        if (s.is(BlockTags.MINEABLE_WITH_PICKAXE)) return "pickaxe";
        if (s.is(BlockTags.MINEABLE_WITH_AXE)) return "axe";
        if (s.is(BlockTags.MINEABLE_WITH_SHOVEL)) return "shovel";
        if (s.is(BlockTags.MINEABLE_WITH_HOE)) return "hoe";
        return null;
    }

    /** Minimum material tier a block requires, from its {@code needs_*_tool} tag.
     *  A block that needs the correct tool but carries no tier tag (e.g. stone)
     *  only needs the wooden tier of the right type. */
    private static String requiredTier(BlockState s) {
        if (s.is(BlockTags.NEEDS_DIAMOND_TOOL)) return "diamond";
        if (s.is(BlockTags.NEEDS_IRON_TOOL)) return "iron";
        if (s.is(BlockTags.NEEDS_STONE_TOOL)) return "stone";
        return "wooden";
    }

    private static String describeHeld(ItemStack tool) {
        return tool.isEmpty()
                ? "bare hands"
                : BuiltInRegistries.ITEM.getKey(tool.getItem()).toString();
    }

    /**
     * Line-of-sight test from the entity's eyes to {@code pos}, mirroring the
     * raycast a vanilla player's block interaction performs: cast to the block
     * centre and require the first block hit to be the target itself. If a wall
     * (or any other block) is hit first the target is occluded — the entity must
     * not mine through cover.
     *
     * <p>Uses {@link ClipContext.Block#OUTLINE} + {@link ClipContext.Fluid#NONE}
     * (same as {@code Entity.pick}); fluids are transparent to the check so the
     * bot can still mine a block it's standing in water next to.
     */
    public static boolean hasLineOfSight(AnimusEntity entity, BlockPos pos) {
        Level level = entity.level();
        Vec3 from = entity.getEyePosition();
        Vec3 to = Vec3.atCenterOf(pos);
        BlockHitResult hit = level.clip(new ClipContext(
                from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity));
        // MISS means the ray reached `to` without hitting any block face — the
        // target's own shape didn't block it (e.g. ray clipped a corner); treat
        // as visible. A BLOCK hit must be the target itself to count as visible.
        return hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(pos);
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
        // Mirror vanilla Player.hasCorrectToolForDrops — a block that doesn't
        // REQUIRE the correct tool (e.g. wood, dirt, sand) is always treated
        // as "correct", regardless of held item. Previously we only checked
        // the tool side, which gave wood-with-bare-hands the slow divisor
        // (100) and made Animus ~3.3× slower than a survival-mode player.
        boolean correct = !state.requiresCorrectToolForDrops()
                || tool.isCorrectToolForDrops(state);
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
        // NOTE: no line-of-sight gate here. This engine digs both the LLM's
        // *target* block AND the obstruction blocks the pathfinder clears to
        // open a route — and while opening a route the entity is right up
        // against the blocks it's breaking, so an eye→block raycast is almost
        // always "occluded" by an adjacent block and would false-positive every
        // tick. Line-of-sight (anti "mine through a wall") is the caller's
        // concern: MineBlockTaskGoal checks {@link #hasLineOfSight} before it
        // commits to mining a target. The pathfinder must be free to dig
        // point-blank cover.
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
            breakAndRouteDrops(level, pos);
            return Outcome.COMPLETED;
        }
        return Outcome.IN_PROGRESS;
    }

    /**
     * Break the block and route every dropped {@link ItemStack} into the
     * Animus's own inventory. Items that don't fit (inventory full) fall back
     * to vanilla on-ground {@link ItemEntity}.
     *
     * <p>Bypasses {@code level.destroyBlock(pos, true, entity)} so we
     * compute drops manually with the entity's held item as the tool
     * (preserves correct-tool / silk-touch / fortune semantics) and never
     * spawn ItemEntities in the world unless the inventory rejects them.
     * The inventory's {@code setChanged} hook pushes a fresh snapshot to the
     * owner so the GUI and {@code get_storage} tool see the new items.
     */
    private void breakAndRouteDrops(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        ItemStack tool = entity.getMainHandItem();
        List<ItemStack> drops = (level instanceof ServerLevel sl)
                ? Block.getDrops(state, sl, pos, blockEntity, entity, tool)
                : List.of();

        // dropResources=false: don't let vanilla spawn ItemEntities; we handle drops.
        level.destroyBlock(pos, false, entity);

        if (drops.isEmpty()) return;

        SimpleContainer inventory = entity.getInventory();
        for (ItemStack stack : drops) {
            ItemStack leftover = inventory.addItem(stack);
            if (!leftover.isEmpty()) {
                Constants.LOG.debug("[animus-mining] inventory full, dropping {} on ground",
                        leftover.getCount());
                spawnAsItemEntity(level, pos, leftover);
            }
        }
    }

    private static void spawnAsItemEntity(Level level, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemEntity ie = new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        ie.setDefaultPickUpDelay();
        level.addFreshEntity(ie);
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
