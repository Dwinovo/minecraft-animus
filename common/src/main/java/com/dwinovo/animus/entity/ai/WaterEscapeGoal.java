package com.dwinovo.animus.entity.ai;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Reflex: when the Animus ends up swimming in deep water (knockback, a failed
 * parkour, a breached aquifer), steer it to the nearest standable shore. Pairs
 * with the vanilla {@code FloatGoal}, which keeps it bobbing at the surface so
 * it can breathe while this goal does the getting-out.
 *
 * <h2>Why a reflex and not the LLM / the custom pathfinder</h2>
 * Same justification as the auto-eat reflex: a drowning bar is ~15 seconds and
 * an LLM round-trip plus task dispatch is far too slow. The custom
 * terrain-modifying pathfinder treats water as impassable by design (it
 * doesn't swim), so it can't plan its own way out — but the VANILLA ground
 * navigation happily paths through water at a malus, which is exactly how
 * vanilla wolves swim out of ponds. We just borrow that.
 *
 * <p>While this runs, the task executors yield (they check
 * {@link AnimusEntity#isDeepInWater()} and idle), then re-plan from dry land —
 * the task survives the swim instead of failing mid-lake.
 */
public final class WaterEscapeGoal extends Goal {

    private static final int SEARCH_HORIZONTAL = 16;
    /** Each failed shore search widens the net by this much, up to the max. */
    private static final int SEARCH_EXPAND_STEP = 8;
    private static final int MAX_SEARCH_HORIZONTAL = 32;
    private static final int SEARCH_VERTICAL = 6;
    /** Re-pick the shore target at most once a second. */
    private static final int RETARGET_INTERVAL_TICKS = 20;
    private static final double SWIM_SPEED = 1.2;
    /** Upward acceleration per tick while the eyes are under water. */
    private static final double SURFACE_THRUST = 0.04;

    private final AnimusEntity entity;
    private int retargetCooldown;
    private int failedSearches;

    public WaterEscapeGoal(AnimusEntity entity) {
        this.entity = entity;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return entity.isDeepInWater();
    }

    @Override
    public boolean canContinueToUse() {
        return entity.isDeepInWater();
    }

    @Override
    public void start() {
        retargetCooldown = 0;
        failedSearches = 0;
        // Claim the body: parks any stale path steering so the swim isn't
        // fighting a leftover MOVE_TO from an interrupted executor tick.
        entity.motor().hold(com.dwinovo.animus.pathing.exec.BodyMotor.Owner.REFLEX);
    }

    @Override
    public void tick() {
        // From depth, actively swim UP every tick. FloatGoal only bobs a body
        // already at the surface; getting TO the surface — the breath bar —
        // is this goal's job (a knockback can dunk the body metres deep).
        if (entity.isUnderWater()) {
            var d = entity.getDeltaMovement();
            entity.motor().impulse(com.dwinovo.animus.pathing.exec.BodyMotor.Owner.REFLEX,
                    d.x, Math.min(d.y + SURFACE_THRUST, 0.25), d.z);
        }
        if (--retargetCooldown > 0 && entity.getNavigation().isInProgress()) {
            return;
        }
        retargetCooldown = RETARGET_INTERVAL_TICKS;
        // Widen the net on every miss: a fixed 16 misses the shore of any
        // decent-sized lake, which used to mean "drift and drown on a timer".
        int reach = Math.min(SEARCH_HORIZONTAL + failedSearches * SEARCH_EXPAND_STEP,
                MAX_SEARCH_HORIZONTAL);
        BlockPos shore = BlockPos.findClosestMatch(
                        entity.blockPosition(), reach, SEARCH_VERTICAL,
                        pos -> BlockHelper.isStandable(entity.level(), pos))
                .orElse(null);
        if (shore != null) {
            failedSearches = 0;
            // Vanilla ground navigation swims (water has a finite path malus);
            // FloatGoal keeps the head above water while it does.
            entity.getNavigation().moveTo(shore.getX() + 0.5, shore.getY(), shore.getZ() + 0.5,
                    SWIM_SPEED);
        } else {
            failedSearches++;
        }
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        entity.motor().release(com.dwinovo.animus.pathing.exec.BodyMotor.Owner.REFLEX);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
