package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.exec.Navigator;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Placer for {@link PlaceBlockTaskRecord}: walk to a standable spot beside the
 * target and place one block from inventory there.
 *
 * <h2>State machine</h2>
 * <pre>
 *   RESOLVE → hold the block? target empty? has a solid neighbour to attach to?
 *             pick a standable spot beside it. In reach → PLACE; else GOTO.
 *   GOTO    → time-sliced A* to the stand spot (bridging/digging like move_to);
 *             in reach of the target → PLACE.
 *   PLACE   → setBlock + consume one from inventory → SUCCESS.
 * </pre>
 *
 * <p>Placement is server-authoritative ({@code level.setBlock} with the block's
 * default state), like {@link CraftTaskGoal}'s table placement — we don't
 * simulate a player right-click. We DO mirror Voyager's "no floating block"
 * rule via the solid-neighbour check, so the model gets taught real placement
 * sense.
 */
public final class PlaceBlockTaskGoal extends LlmTaskGoal<PlaceBlockTaskRecord> {

    private enum Phase { RESOLVE, GOTO, PLACE }

    private static final double WALK_SPEED = 1.0;

    private Phase phase = Phase.RESOLVE;
    private BlockPos standSpot;
    private Navigator nav;

    private String doneReason = "done";
    private final Map<String, Object> resultData = new HashMap<>();

    public PlaceBlockTaskGoal(AnimusEntity entity) {
        super(entity, PlaceBlockTaskRecord.TOOL_NAME, PlaceBlockTaskRecord.class);
    }

    @Override
    protected void onStart(PlaceBlockTaskRecord r) {
        this.phase = Phase.RESOLVE;
        this.standSpot = null;
        this.nav = null;
        this.resultData.clear();
    }

    @Override
    protected void onTick(PlaceBlockTaskRecord r) {
        switch (phase) {
            case RESOLVE -> tickResolve(r);
            case GOTO -> tickGoto(r);
            case PLACE -> tickPlace(r);
        }
    }

    private void tickResolve(PlaceBlockTaskRecord r) {
        Level level = entity.level();
        if (entity.getInventory().countItem(r.item) <= 0) {
            fail("no " + r.label + " in inventory to place");
            return;
        }
        if (!BlockHelper.isReplaceableForPlacement(level, r.pos)) {
            failSuggesting(level, r.pos, "target " + coords(r.pos) + " is occupied by "
                    + net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(level.getBlockState(r.pos).getBlock()).getPath()
                    + " — break_block that exact cell first, or place at a free cell");
            return;
        }
        if (solidNeighbour(level, r.pos) == null) {
            failSuggesting(level, r.pos, "can't place a floating " + r.label + " at " + coords(r.pos)
                    + " — it needs a solid block next to it to attach to");
            return;
        }
        if (occupiesSelf(r.pos)) {
            failSuggesting(level, r.pos, "can't place a block on myself — "
                    + coords(r.pos) + " is where I'm standing");
            return;
        }
        if (withinReach(r.pos)) {
            phase = Phase.PLACE;
            return;
        }
        standSpot = findStandSpot(level, r.pos);
        if (standSpot == null) {
            failSuggesting(level, r.pos, "no reachable spot to stand and place at " + coords(r.pos));
            return;
        }
        // Walk to the stand spot, but arrival = within reach of the TARGET cell
        // (we may get close enough before fully reaching standSpot).
        nav = new Navigator(entity, standSpot, WALK_SPEED, () -> withinReach(r.pos));
        phase = Phase.GOTO;
    }

    private void tickGoto(PlaceBlockTaskRecord r) {
        switch (nav.tick()) {
            case RUNNING -> { /* keep walking; planned while moving */ }
            case ARRIVED -> { nav.stop(); phase = Phase.PLACE; }
            case FAILED -> {
                if (withinReach(r.pos)) {
                    nav.stop();
                    phase = Phase.PLACE;
                } else {
                    // The navigator's reason carries the teachable part
                    // ("blocked by a gap and no bridging blocks — give me
                    // cobblestone or dirt") — never drop it.
                    failSuggesting(entity.level(), r.pos,
                            "can't reach a spot to stand and place at " + coords(r.pos)
                                    + " (" + nav.failReason() + ")");
                }
            }
        }
    }

    private void tickPlace(PlaceBlockTaskRecord r) {
        Level level = entity.level();
        // Re-validate — the world or our footing may have shifted while walking.
        if (!BlockHelper.isReplaceableForPlacement(level, r.pos)) {
            failSuggesting(level, r.pos,
                    "target " + coords(r.pos) + " got occupied before I could place");
            return;
        }
        if (occupiesSelf(r.pos)) {
            failSuggesting(level, r.pos, "can't place at " + coords(r.pos) + " — I'm standing there");
            return;
        }
        BlockPos ref = solidNeighbour(level, r.pos);
        if (ref == null) {
            failSuggesting(level, r.pos, "can't place a floating " + r.label + " at "
                    + coords(r.pos) + " — no solid block to attach to");
            return;
        }
        int slot = firstSlotOf(r.item);
        if (slot < 0) {
            fail("no " + r.label + " left to place");
            return;
        }

        // Place like a player: click the reference block's face that points at the
        // target, so vanilla's BlockItem.place puts our block at r.pos with a
        // proper, context-derived state (stairs/log/chest facing, etc.). The fake
        // player consumes one item; FakePlayerUse reconciles it back to inventory.
        Direction face = directionFromTo(ref, r.pos);
        Vec3 hitVec = Vec3.atCenterOf(ref).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        FakePlayerUse.useOnBlock(entity, slot, new BlockHitResult(hitVec, face, ref, false));

        if (!level.getBlockState(r.pos).is(r.block)) {
            // Vanilla refused the right-click: a block-specific rule we don't
            // model (torch needs a full face, door needs the cell above, crop
            // needs farmland, …). The suggestions are generic cells; the model
            // pairs them with the block's own rules.
            failSuggesting(level, r.pos, "placement of " + r.label + " didn't land at "
                    + coords(r.pos) + " — this block type refused that spot (some blocks "
                    + "need a full solid face, specific ground, or two free cells)");
            return;
        }

        resultData.put("x", r.pos.getX());
        resultData.put("y", r.pos.getY());
        resultData.put("z", r.pos.getZ());
        resultData.put("block", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(r.block).toString());
        doneReason = "placed " + r.label + " at " + coords(r.pos);
        currentRecord.setState(TaskState.SUCCESS);
    }

    // ---- helpers ----

    /** A real (non-air, non-replaceable) neighbour block the new block can attach to, or null. */
    private static BlockPos solidNeighbour(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos n = pos.relative(dir);
            BlockState s = level.getBlockState(n);
            if (!s.isAir() && !s.canBeReplaced()) return n;
        }
        return null;
    }

    /** The Direction d such that {@code from.relative(d) == to} (they must be neighbours). */
    private static Direction directionFromTo(BlockPos from, BlockPos to) {
        return Direction.getApproximateNearest(
                to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
    }

    private int firstSlotOf(Item item) {
        SimpleContainer inv = entity.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.getItem() == item) return i;
        }
        return -1;
    }

    /** The target cell is one the entity currently occupies (feet or head). */
    private boolean occupiesSelf(BlockPos pos) {
        BlockPos feet = entity.blockPosition();
        return pos.equals(feet) || pos.equals(feet.above());
    }

    /** Nearest standable cell beside the target (its horizontal neighbours, and
     *  those one below) so the entity can stand next to it and reach it. */
    private BlockPos findStandSpot(Level level, BlockPos target) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int drop = 0; drop <= 1; drop++) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos cand = target.relative(dir).below(drop);
                if (!BlockHelper.isStandable(level, cand)) continue;
                double d = entity.distanceToSqr(Vec3.atCenterOf(cand));
                if (d < bestDist) {
                    bestDist = d;
                    best = cand;
                }
            }
        }
        return best;
    }

    private boolean withinReach(BlockPos pos) {
        return entity.onGround()
                && entity.distanceToSqr(Vec3.atCenterOf(pos)) <= BlockMiningProgress.REACH_SQR;
    }

    private static String coords(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private void fail(String reason) {
        doneReason = reason;
        currentRecord.setState(TaskState.FAILED);
    }

    /** Radius scanned around a failed target for placeable alternatives. */
    private static final int SUGGEST_RANGE_H = 3;
    private static final int SUGGEST_RANGE_V = 2;
    private static final int MAX_SUGGESTIONS = 5;

    /**
     * Fail with placement guidance: append up to {@link #MAX_SUGGESTIONS} nearby
     * cells where a block COULD be placed right now, nearest-to-target first.
     * The model burns a lot of turns probing coordinates blindly after a failed
     * placement — handing it working alternatives turns that into one retry.
     */
    private void failSuggesting(Level level, BlockPos target, String reason) {
        List<BlockPos> spots = suggestSpots(level, target);
        if (spots.isEmpty()) {
            fail(reason + ". No placeable cell within " + SUGGEST_RANGE_H
                    + " blocks of it either — pick a spot adjacent to existing solid blocks.");
            return;
        }
        StringBuilder sb = new StringBuilder(reason)
                .append(". Nearby cells where placement WOULD work right now (nearest first): ");
        for (int i = 0; i < spots.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append('(').append(coords(spots.get(i))).append(')');
        }
        fail(sb.toString());
    }

    /**
     * Cells near {@code target} that pass the same checks a placement there
     * would: empty/replaceable, attached to something solid, not inside the
     * entity, and not a sealed pocket (at least one open side, so it is
     * visible/reachable rather than buried in terrain).
     */
    private List<BlockPos> suggestSpots(Level level, BlockPos target) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos c : BlockPos.betweenClosed(
                target.offset(-SUGGEST_RANGE_H, -SUGGEST_RANGE_V, -SUGGEST_RANGE_H),
                target.offset(SUGGEST_RANGE_H, SUGGEST_RANGE_V, SUGGEST_RANGE_H))) {
            if (c.equals(target)) continue;
            if (!BlockHelper.isReplaceableForPlacement(level, c)) continue;
            if (solidNeighbour(level, c) == null) continue;
            if (occupiesSelf(c)) continue;
            if (!hasOpenSide(level, c)) continue;
            out.add(c.immutable());
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(target)));
        return out.size() > MAX_SUGGESTIONS ? out.subList(0, MAX_SUGGESTIONS) : out;
    }

    /** At least one neighbour the entity's body could occupy — filters out
     *  air pockets fully enclosed in terrain, which would suggest unreachable spots. */
    private static boolean hasOpenSide(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (BlockHelper.canWalkThrough(level, pos.relative(dir))) return true;
        }
        return false;
    }

    @Override
    protected TaskResult buildResult(PlaceBlockTaskRecord r, TaskState finalState) {
        if (nav != null) nav.stop();
        entity.getNavigation().stop();
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, resultData);
            case TIMEOUT -> TaskResult.timeout("timed out before placing " + r.label);
            case CANCELLED -> TaskResult.cancelled("place_block interrupted");
            case FAILED -> TaskResult.fail(doneReason);
            default -> TaskResult.fail("unexpected state: " + finalState);
        };
    }
}
