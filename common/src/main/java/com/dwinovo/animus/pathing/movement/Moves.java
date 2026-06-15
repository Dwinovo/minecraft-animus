package com.dwinovo.animus.pathing.movement;

import com.dwinovo.animus.pathing.calc.NavContext;
import com.dwinovo.animus.pathing.util.ActionCosts;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.pathing.util.PathSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the candidate {@link Movement}s reachable from a feet-position,
 * each self-costed against the {@link NavContext} snapshot. Infeasible
 * movements (no scaffolding to bridge, unbreakable obstruction, hazard,
 * fall too deep) are simply not emitted.
 *
 * <h2>Primitive set (mineflayer-parity)</h2>
 * <ul>
 *   <li><b>Traverse</b> — same-Y step; bridges gaps by placing a floor block
 *       and mines head/feet obstructions.</li>
 *   <li><b>Ascend</b> — step up one block; mines head-room and may place the
 *       step block to climb a ledge that isn't there.</li>
 *   <li><b>Descend / Fall</b> — step out and drop to the first safe floor
 *       within {@link NavContext#maxFallHeight}.</li>
 *   <li><b>Diagonal</b> — same-Y step to a corner cell; emitted only when both
 *       orthogonal corner cells are open (no corner-clipping), mirrors
 *       Baritone {@code MovementDiagonal} / mineflayer diagonal moves.</li>
 *   <li><b>Pillar</b> — jump straight up one block, placing a scaffolding block
 *       beneath as you rise (mineflayer "move up" / jump-place). Needs a
 *       scaffold block and head-room.</li>
 *   <li><b>DigDown</b> — mine the floor block underfoot and drop one (mineflayer
 *       "dig down"). Only when a solid floor exists one block lower to land on.</li>
 *   <li><b>Parkour</b> — a committed running jump across a 2-4 block gap at the
 *       same level (Baritone {@code MovementParkour}); the executor supplies
 *       the impulse, the planner only asserts the corridor is jumpable.</li>
 * </ul>
 */
public final class Moves {

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    /** The four diagonal corners as orthogonal direction pairs. */
    private static final Direction[][] DIAGONALS = {
            {Direction.NORTH, Direction.EAST},
            {Direction.NORTH, Direction.WEST},
            {Direction.SOUTH, Direction.EAST},
            {Direction.SOUTH, Direction.WEST},
    };

    /** Maximum parkour gap (blocks) — 4 needs sprint physics, the vanilla cap. */
    private static final int MAX_PARKOUR = 4;

    private Moves() {}

    /** All feasible movements out of {@code from}. */
    public static List<Movement> generate(NavContext ctx, BlockPos from) {
        List<Movement> out = new ArrayList<>(18);
        for (Direction dir : HORIZONTAL) {
            Movement t = traverse(ctx, from, dir);
            if (t != null) out.add(t);
            Movement a = ascend(ctx, from, dir);
            if (a != null) out.add(a);
            Movement d = descend(ctx, from, dir);
            if (d != null) out.add(d);
            Movement p = parkour(ctx, from, dir);
            if (p != null) out.add(p);
        }
        for (Direction[] pair : DIAGONALS) {
            Movement g = diagonal(ctx, from, pair[0], pair[1]);
            if (g != null) out.add(g);
        }
        Movement up = pillar(ctx, from);
        if (up != null) out.add(up);
        Movement down = digDown(ctx, from);
        if (down != null) out.add(down);
        return out;
    }

    // ---- Traverse: same-Y step, bridge gaps, dig obstructions ----

    private static Movement traverse(NavContext ctx, BlockPos from, Direction dir) {
        BlockGetter level = ctx.view;
        BlockPos dest = from.relative(dir);
        BlockPos head = dest.above();

        List<BlockPos> toBreak = new ArrayList<>(2);

        // Clear the two body cells at the destination.
        double feetBreak = clearCost(ctx, dest, toBreak);
        if (feetBreak >= ActionCosts.COST_INF) return null;
        double headBreak = clearCost(ctx, head, toBreak, true);   // top cell: fold falling stack
        if (headBreak >= ActionCosts.COST_INF) return null;

        if (BlockHelper.isHazard(level, dest) || BlockHelper.isHazard(level, head)) return null;

        BlockPos floor = dest.below();
        if (BlockHelper.canWalkOn(level, floor)) {
            // WALK branch (Baritone MovementTraverse walk case). Water dominates: if
            // either body cell at the destination is water we float through it at
            // waterWalkSpeed (no sprint, no soul-sand term — we're not touching the
            // floor). Otherwise base WALK + half soul-sand penalty per soul-sand floor
            // touched (destOn + srcDown), and sprint when nothing needs breaking.
            boolean water = BlockHelper.isWater(level, dest) || BlockHelper.isWater(level, head);
            double walk;
            if (water) {
                walk = ActionCosts.WALK_ONE_IN_WATER;   // no depth-strider modelled
            } else {
                walk = ActionCosts.WALK_ONE_BLOCK;
                double soulSandHalf =
                        (ActionCosts.WALK_ONE_OVER_SOUL_SAND - ActionCosts.WALK_ONE_BLOCK) / 2.0;
                if (level.getBlockState(floor).is(Blocks.SOUL_SAND)) {
                    walk += soulSandHalf;
                } else if (level.getBlockState(floor).is(Blocks.WATER)) {
                    // Standing ON a water surface (Baritone: WC += walkOnWaterOnePenalty).
                    walk += PathSettings.WALK_ON_WATER_ONE_PENALTY;
                }
                if (level.getBlockState(from.below()).is(Blocks.SOUL_SAND)) walk += soulSandHalf;
            }
            double cost = (!water && feetBreak == 0.0 && headBreak == 0.0 && ctx.canSprint)
                    ? walk * ActionCosts.SPRINT_MULTIPLIER
                    : walk + feetBreak + headBreak;
            return new Movement(Movement.Kind.TRAVERSE, from, dest, cost, toBreak, null);
        }

        // BRIDGE branch (Baritone MovementTraverse): the dest floor is missing, so place
        // one. Needs a face to place against — a side face lets us walk-place; if only
        // the block we're standing on backs it, we must sneak-backplace (× SNEAK/WALK);
        // with no support at all (or off a ladder/vine / soul sand) it's impossible.
        // Baritone uses a plain WALK here (no soul-sand term in the bridge branch).
        if (isLadderOrVine(level.getBlockState(from.below()))) return null;
        double placeCost = ctx.costOfPlacing(floor);   // INF if non-replaceable / no scaffold / hazard
        if (placeCost >= ActionCosts.COST_INF) return null;
        double walkMult = bridgeSupport(ctx, from, floor);
        if (walkMult >= ActionCosts.COST_INF) return null;
        double cost = ActionCosts.WALK_ONE_BLOCK * walkMult + feetBreak + headBreak + placeCost;
        return new Movement(Movement.Kind.TRAVERSE, from, dest, cost, toBreak, floor);
    }

    /** The 5 faces of the dest floor a bridge can place against (Baritone's
     *  HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP). */
    private static final Direction[] SUPPORT_DIRS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    /**
     * Baritone MovementTraverse bridge support scan: walk-cost MULTIPLIER for placing
     * the floor block. A side face (any of the 5 except the block we stand on) that we
     * can place against → 1.0 (normal walk-place). Otherwise we must backplace against
     * the block under our feet → {@code SNEAK/WALK} (≈3.3, a slow sneak place), vetoed
     * on soul sand (can't sneak-backplace off it). No placeable face → {@code COST_INF}.
     */
    private static double bridgeSupport(NavContext ctx, BlockPos from, BlockPos floor) {
        BlockGetter level = ctx.view;
        BlockPos sourceBelow = from.below();
        for (Direction d : SUPPORT_DIRS) {
            BlockPos against = floor.relative(d);
            if (against.equals(sourceBelow)) continue;   // that's the backplace face, handled below
            if (canPlaceAgainst(level, against)) return 1.0;
        }
        // Sneak-backplace against the block under our feet. Baritone gates this on
        // mustBeSolidToWalkOn(srcDown), NOT on a real solid cube — and that is TRUE for
        // air, so a void bridge CHAINS (the block below us was placed by the previous
        // bridge step, even though the static world snapshot still shows air here).
        if (!BlockHelper.mustBeSolidToWalkOn(level, sourceBelow)) return ActionCosts.COST_INF;
        if (level.getBlockState(sourceBelow).is(Blocks.SOUL_SAND)) return ActionCosts.COST_INF;
        return ActionCosts.SNEAK_ONE_BLOCK / ActionCosts.WALK_ONE_BLOCK;
    }

    /** Baritone canPlaceAgainst: a normal cube / glass presents a face to place against
     *  (NOT every full-collision block — see {@link BlockHelper#canPlaceAgainst}). */
    private static boolean canPlaceAgainst(BlockGetter level, BlockPos pos) {
        return BlockHelper.canPlaceAgainst(level, pos);
    }

    /** Baritone MovementAscend place support: a face to place the step block against,
     *  excluding our own source column (no backplace mid-jump). */
    private static boolean ascendPlaceSupported(NavContext ctx, BlockPos from, BlockPos step) {
        BlockGetter level = ctx.view;
        for (Direction d : SUPPORT_DIRS) {
            BlockPos against = step.relative(d);
            if (against.getX() == from.getX() && against.getZ() == from.getZ()) continue;
            if (canPlaceAgainst(level, against)) return true;
        }
        return false;
    }

    // ---- Ascend: up one block, dig head-room, place step if needed ----

    private static Movement ascend(NavContext ctx, BlockPos from, Direction dir) {
        BlockGetter level = ctx.view;
        BlockPos dest = from.relative(dir).above(); // feet one up & over
        BlockPos destHead = dest.above();
        BlockPos jumpRoom = from.above(2);          // room to jump at the source column
        BlockPos step = dest.below();               // == from.relative(dir): floor we stand on after the step

        // Baritone MovementAscend suffocation veto: a FallingBlock at y+3 (above the
        // jump apex) would fall onto us once we clear the stack over our head. Refuse,
        // unless y+1 is already solid AND y+2 is itself falling (then we'd have had to
        // clear the whole stack to even stand here, so nothing is left to fall).
        if (level.getBlockState(from.above(3)).getBlock()
                    instanceof net.minecraft.world.level.block.FallingBlock
                && (BlockHelper.canWalkThrough(level, from.above(1))
                    || !(level.getBlockState(jumpRoom).getBlock()
                            instanceof net.minecraft.world.level.block.FallingBlock))) {
            return null;
        }
        // Can't jump-ascend off a ladder/vine (Baritone srcDown check).
        if (isLadderOrVine(level.getBlockState(from.below()))) return null;

        // Baritone MovementAscend: max(JUMP, WALK) + jumpPenalty (the jump and the
        // forward block overlap, so it's the larger of the two, not their sum) —
        // EXCEPT stepping ONTO soul sand costs the soul-sand walk instead, matching
        // how traverse/descend already price soul sand. (Bottom-slab ascend special
        // cases are not modelled — slabs aren't modelled anywhere in our move set.)
        double base = level.getBlockState(step).is(Blocks.SOUL_SAND)
                ? ActionCosts.WALK_ONE_OVER_SOUL_SAND
                : Math.max(ActionCosts.JUMP_ONE_BLOCK, ActionCosts.WALK_ONE_BLOCK);
        double cost = base + PathSettings.JUMP_PENALTY;
        List<BlockPos> toBreak = new ArrayList<>(3);

        double jumpBreak = clearCost(ctx, jumpRoom, toBreak);
        if (jumpBreak >= ActionCosts.COST_INF) return null;
        cost += jumpBreak;

        double feetBreak = clearCost(ctx, dest, toBreak);
        if (feetBreak >= ActionCosts.COST_INF) return null;
        cost += feetBreak;
        double headBreak = clearCost(ctx, destHead, toBreak, true);   // top cell: fold falling stack
        if (headBreak >= ActionCosts.COST_INF) return null;
        cost += headBreak;

        // The block we stand ON after the step (floor under dest) — `step` above.
        BlockPos toPlace = null;
        if (!BlockHelper.canWalkOn(level, step)) {
            double placeCost = ctx.costOfPlacing(step);
            if (placeCost >= ActionCosts.COST_INF) return null;
            // Baritone MovementAscend: the step block needs SOME face to place against
            // other than our own source column — we can't backplace mid-jump.
            if (!ascendPlaceSupported(ctx, from, step)) return null;
            cost += placeCost;
            toPlace = step;
        }

        if (BlockHelper.isHazard(level, dest) || BlockHelper.isHazard(level, destHead)) return null;
        return new Movement(Movement.Kind.ASCEND, from, dest, cost, toBreak, toPlace);
    }

    // ---- Descend / Fall: step out and drop to first safe floor ----

    private static Movement descend(NavContext ctx, BlockPos from, Direction dir) {
        BlockGetter level = ctx.view;
        BlockPos col = from.relative(dir);          // (destX, y, destZ)
        BlockPos landing = col.below();             // (destX, y-1): single-descend feet

        // Baritone MovementDescend breaks THREE cells of the dest column — the
        // landing-foot cell (y-1) plus the two body cells at source height (y, y+1)
        // — and that "frontBreak" applies to a single step-down AND to a longer fall.
        List<BlockPos> toBreak = new ArrayList<>(3);
        double frontBreak = 0;
        BlockPos[] frontCells = {landing, col, col.above()};   // col.above() is the top cell
        for (int i = 0; i < frontCells.length; i++) {
            double b = clearCost(ctx, frontCells[i], toBreak, i == frontCells.length - 1);
            if (b >= ActionCosts.COST_INF) return null;
            frontBreak += b;
        }
        // Can't descend off a ladder/vine — you'd climb it instead (Baritone fromDown check).
        if (isLadderOrVine(level.getBlockState(from.below()))) return null;

        BlockPos belowLanding = col.below(2);       // (destX, y-2)
        if (BlockHelper.canWalkOn(level, belowLanding)) {
            // Single-block descend: solid floor right below the landing. Walk off the
            // edge (soul-sand-scaled) + max(fall(1), center-after-fall).
            if (isLadderOrVine(level.getBlockState(landing))) return null;
            if (BlockHelper.isHazard(level, landing) || BlockHelper.isHazard(level, belowLanding)) return null;
            double walk = ActionCosts.WALK_OFF_BLOCK;
            if (level.getBlockState(from.below()).is(Blocks.SOUL_SAND)) {
                walk *= ActionCosts.WALK_ONE_OVER_SOUL_SAND / ActionCosts.WALK_ONE_BLOCK;
            }
            double total = frontBreak + walk
                    + Math.max(ActionCosts.fallCost(1), ActionCosts.CENTER_AFTER_FALL);
            return new Movement(Movement.Kind.DESCEND, from, landing, total, toBreak, null);
        }

        // Longer fall (Baritone dynamicFallCost). The landing cell must be air to fall
        // through; scan down for the first non-air cell. Two landing kinds, mirroring
        // Baritone's loop order (water checked BEFORE the air-continue):
        //   • WATER — negates fall damage, so any height is safe; feet land floating in
        //     the surface water cell (res.y = the water cell, not one above it).
        //   • DRY floor — feet land one above it (y-fallHeight+1), bounded by
        //     maxFallHeightNoWater + 1.
        // Charged FALL_N[fallHeight] (fallHeight, NOT blocks-dropped: a fall to y-2 is
        // fallHeight 3). Bucket-MLG and vine/ladder fall-speed resets remain out of
        // scope; columns with those cost the full height or aren't emitted.
        if (!BlockHelper.canWalkThrough(level, belowLanding)) return null;
        // Baritone dynamicFallCost guard: if we're breaking the front column AND a
        // FallingBlock sits just above it (y+2), breaking would drop that block into
        // the fall path — veto. (frontBreak==0 means we slip through without disturbing it.)
        if (frontBreak != 0
                && level.getBlockState(col.above(2)).getBlock()
                        instanceof net.minecraft.world.level.block.FallingBlock) {
            return null;
        }
        for (int fallHeight = 3; fallHeight <= PathSettings.MAX_FALL_HEIGHT_BUCKET + 1; fallHeight++) {
            BlockPos onto = col.below(fallHeight);   // (destX, y-fallHeight): landing candidate
            if (BlockHelper.isWater(level, onto)) {
                // Fall into water (Baritone dynamicFallCost water branch).
                if (!BlockHelper.canWalkThrough(level, onto)) return null;     // submerged, not a surface cell
                if (BlockHelper.isFlowingWater(level, onto)) return null;
                if (!BlockHelper.canWalkOn(level, onto.below())) return null;  // don't punch through into a void
                double totalW = ActionCosts.WALK_OFF_BLOCK + ActionCosts.fallCost(fallHeight) + frontBreak;
                return new Movement(Movement.Kind.FALL, from, onto, totalW, toBreak, null);
            }
            if (BlockHelper.canWalkThrough(level, onto)) continue;   // still air — keep falling
            if (fallHeight > ctx.maxFallHeight + 1) return null;     // dry landing beyond safe height
            if (!BlockHelper.canWalkOn(level, onto)) return null;    // not standable — abort
            BlockPos feet = onto.above();                            // (destX, y-fallHeight+1)
            if (BlockHelper.isHazard(level, onto) || BlockHelper.isHazard(level, feet)) return null;
            double total = ActionCosts.WALK_OFF_BLOCK + ActionCosts.fallCost(fallHeight) + frontBreak;
            return new Movement(Movement.Kind.FALL, from, feet, total, toBreak, null);
        }
        return null;
    }

    /** A ladder or vine — you climb these, so they block stepping off / can reset a fall. */
    private static boolean isLadderOrVine(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.LADDER) || state.is(Blocks.VINE);
    }

    // ---- Diagonal: same-Y corner step over EXISTING ground only ----

    /**
     * Same-Y corner step — a 1:1 port of Baritone {@code MovementDiagonal.cost}'s
     * flat case (diagonal ascend/descend stay disabled, matching
     * {@code allowDiagonalAscend/Descend=false}). It neither breaks nor places:
     * instead it requires at least ONE of the two cut corners be passable and
     * "edges around" that side. Both corners clear → it may sprint; exactly one
     * clear → it hugs that corner at {@code ×(SQRT_2−0.001)} (no sprint); both
     * blocked → not possible. Water floats through at {@code waterWalkSpeed};
     * soul sand under either touched floor adds its half-penalty.
     */
    private static Movement diagonal(NavContext ctx, BlockPos from, Direction a, Direction b) {
        BlockGetter level = ctx.view;
        BlockPos dest = from.relative(a).relative(b);

        // Head-room above the destination, and body clearance at the dest cell.
        if (!BlockHelper.canWalkThrough(level, dest.above())) return null;
        if (!BlockHelper.canWalkThrough(level, dest)) return null;     // flat only (no diagonal ascend)

        BlockPos destFloor = dest.below();
        boolean water = BlockHelper.isWater(level, from) || BlockHelper.isWater(level, dest);
        // Flat requires a floor under dest (or floating on water); no diagonal descend.
        if (!water && !BlockHelper.canWalkOn(level, destFloor)) return null;

        // Can't diagonal off a ladder/vine (Baritone fromDown ladder/vine veto).
        if (isLadderOrVine(level.getBlockState(from.below()))) return null;

        if (BlockHelper.isHazard(level, dest) || BlockHelper.isHazard(level, dest.above())) return null;

        double multiplier;
        if (water) {
            // Floating: floor material is irrelevant, ignore soul-sand terms.
            multiplier = ActionCosts.WALK_ONE_IN_WATER;
        } else {
            multiplier = ActionCosts.WALK_ONE_BLOCK;
            double soulSandHalf =
                    (ActionCosts.WALK_ONE_OVER_SOUL_SAND - ActionCosts.WALK_ONE_BLOCK) / 2.0;
            if (level.getBlockState(destFloor).is(Blocks.SOUL_SAND)) {
                multiplier += soulSandHalf;
            } else if (level.getBlockState(destFloor).is(Blocks.WATER)) {
                // Standing ON a water surface (Baritone: multiplier += walkOnWaterOnePenalty * SQRT_2,
                // which the final × SQRT_2 turns into a penalty of 2 per block).
                multiplier += PathSettings.WALK_ON_WATER_ONE_PENALTY * ActionCosts.SQRT_2;
            }
            if (level.getBlockState(from.below()).is(Blocks.SOUL_SAND)) multiplier += soulSandHalf;
        }

        // The two corners we cut between (Baritone optionA / optionB). A corner is
        // "clear" when both its body cells are passable (we never break to diagonal,
        // so a breakable corner counts as blocked). Both blocked → impossible.
        BlockPos cornerA = from.relative(a);
        BlockPos cornerB = from.relative(b);
        boolean clearA = BlockHelper.canWalkThrough(level, cornerA)
                && BlockHelper.canWalkThrough(level, cornerA.above());
        boolean clearB = BlockHelper.canWalkThrough(level, cornerB)
                && BlockHelper.canWalkThrough(level, cornerB.above());
        if (!clearA && !clearB) return null;

        if (clearA && clearB) {
            // Not edging around anything → sprint, unless in water.
            if (ctx.canSprint && !water) multiplier *= ActionCosts.SPRINT_MULTIPLIER;
        } else {
            // Edge around the one clear corner — costs slightly more than a clean
            // diagonal and can't sprint (Baritone ×(SQRT_2 − 0.001)).
            multiplier *= ActionCosts.SQRT_2 - 0.001;
        }

        double cost = multiplier * ActionCosts.SQRT_2;
        return new Movement(Movement.Kind.DIAGONAL, from, dest, cost, List.of(), null);
    }

    // ---- Pillar: jump up one, placing a block beneath as we rise ----

    private static Movement pillar(NavContext ctx, BlockPos from) {
        BlockGetter level = ctx.view;
        // Never pillar out of water: the jump cycle needs onGround, which a
        // floating body never has — the move would just stall and churn
        // replans. Height from water is "swim to shore first" by design.
        if (BlockHelper.isWater(level, from)) return null;
        BlockPos dest = from.above();      // feet end one block up
        BlockPos newHead = from.above(2);  // head room while standing on the new block

        // Need a scaffold block to drop under our feet (the current feet cell).
        double placeCost = ctx.costOfPlacing(from);
        if (placeCost >= ActionCosts.COST_INF) return null;
        // Baritone MovementPillar: +0.1 tick when what's below our feet is currently
        // air — slightly penalise pillaring on air vs on solid ground.
        if (level.getBlockState(from.below()).isAir()) {
            placeCost += 0.1;
        }

        // Baritone MovementPillar (block tower): jump + place-underfoot + jumpPenalty.
        double cost = ActionCosts.JUMP_ONE_BLOCK + placeCost + PathSettings.JUMP_PENALTY;
        List<BlockPos> toBreak = new ArrayList<>(1);
        double headBreak = clearCost(ctx, newHead, toBreak, true);   // top cell: fold falling stack
        if (headBreak >= ActionCosts.COST_INF) return null;
        cost += headBreak;

        if (BlockHelper.isHazard(level, dest) || BlockHelper.isHazard(level, newHead)) return null;
        // toPlace deliberately null: placement is the PILLAR phase's own job,
        // done mid-jump at the entity's LIVE base Y (which may differ from the
        // plan by a block) — the generic PREPARE_PLACE phase never runs for
        // pillars, so a toPlace here would be dead, misleading data.
        return new Movement(Movement.Kind.PILLAR, from, dest, cost, toBreak, null);
    }

    // ---- DigDown: mine the floor underfoot and drop one ----

    private static Movement digDown(NavContext ctx, BlockPos from) {
        if (!PathSettings.ALLOW_DOWNWARD) return null;
        BlockGetter level = ctx.view;
        BlockPos below = from.below();       // floor block to mine == destination feet
        BlockPos landing = from.below(2);    // must be solid to stand on after the drop

        if (!BlockHelper.canWalkOn(level, landing)) return null;

        double breakCost = ctx.costOfBreaking(below);
        if (breakCost >= ActionCosts.COST_INF) return null; // air/unbreakable handled by descend
        if (BlockHelper.isHazard(level, below) || BlockHelper.isHazard(level, landing)) return null;

        List<BlockPos> toBreak = new ArrayList<>(1);
        toBreak.add(below.immutable());
        double cost = breakCost + ActionCosts.fallCost(1);
        return new Movement(Movement.Kind.DIG_DOWN, from, below, cost, toBreak, null);
    }

    // ---- Parkour: a running jump across a 2-4 block gap at the same level ----

    /**
     * A single atomic edge that clears a gap by jumping, rather than bridging it
     * with scaffolding (Baritone {@code MovementParkour}). Emitted only when
     * there's a genuine gap straight ahead (no floor immediately in front) and a
     * clear air corridor at body height across to the nearest landing within
     * {@link #MAX_PARKOUR}. The momentum + jump timing are supplied by the
     * executor at runtime; the planner only asserts the gap is jumpable.
     *
     * <p>Conservative scope: flat (same-Y) landings only — ascend/drop parkour
     * and parkour-place are deferred. Neither breaks nor places anything.
     */
    private static Movement parkour(NavContext ctx, BlockPos from, Direction dir) {
        if (!PathSettings.ALLOW_PARKOUR) return null;   // Baritone default: parkour off
        BlockGetter level = ctx.view;
        // Only a real gap warrants a jump: a floor immediately ahead means a
        // plain traverse/descend already covers it.
        if (BlockHelper.canWalkOn(level, from.relative(dir).below())) return null;
        // Head-room to jump from the takeoff.
        if (!BlockHelper.canWalkThrough(level, from.above(2))) return null;

        for (int d = 2; d <= MAX_PARKOUR; d++) {
            // Every cell from 1..d must be clear air at feet & head (no clipping).
            boolean clear = true;
            for (int i = 1; i <= d; i++) {
                BlockPos g = from.relative(dir, i);
                if (!BlockHelper.canWalkThrough(level, g)
                        || !BlockHelper.canWalkThrough(level, g.above())) {
                    clear = false;
                    break;
                }
                // The arc rises ~1.25, so over the gap interior the head sweeps
                // the third cell too — a 3-high ceiling bonks the jump short.
                if (i < d && !BlockHelper.canWalkThrough(level, g.above(2))) {
                    clear = false;
                    break;
                }
            }
            if (!clear) break;   // blocked within the gap → no longer jump is possible

            BlockPos land = from.relative(dir, d);
            if (!BlockHelper.canWalkOn(level, land.below())) continue;   // no floor here, try farther
            if (BlockHelper.isHazard(level, land) || BlockHelper.isHazard(level, land.above())) break;
            double cost = ActionCosts.costFromJumpDistance(d);
            return new Movement(Movement.Kind.PARKOUR, from, land, cost, List.of(), null);
        }
        return null;
    }

    /**
     * Cost to make {@code cell} body-passable: 0 if already clear, the break
     * cost (and adds it to {@code toBreak}) if a breakable obstruction,
     * {@link ActionCosts#COST_INF} if unbreakable.
     */
    private static double clearCost(NavContext ctx, BlockPos cell, List<BlockPos> toBreak) {
        return clearCost(ctx, cell, toBreak, false);
    }

    /** {@code includeFalling}: this is the TOP cell the move breaks, so fold in the
     *  cost of the FallingBlock stack above it (it cascades down as we dig). */
    private static double clearCost(NavContext ctx, BlockPos cell, List<BlockPos> toBreak,
                                    boolean includeFalling) {
        if (BlockHelper.canWalkThrough(ctx.view, cell)) return 0.0;
        double breakCost = ctx.costOfBreaking(cell, includeFalling);
        if (breakCost >= ActionCosts.COST_INF) return ActionCosts.COST_INF;
        toBreak.add(cell.immutable());
        return breakCost;
    }
}
