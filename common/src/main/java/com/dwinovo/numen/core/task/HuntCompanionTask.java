package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.exec.Interaction;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.CompanionTask;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.task.TaskState;
import com.google.common.collect.Multimap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code hunt} on the player body: find / chase / fight N mobs. The combat twin
 * of {@code HuntTaskGoal}, but melee is the player's NATIVE attack
 * ({@code player.attack} with real cooldown / weapon modifiers / sweep / crit)
 * instead of the Mob's MeleeEngine — and the chase is {@link PlayerNav}.
 */
public final class HuntCompanionTask implements CompanionTask {

    private enum Phase { SCAN, ENGAGE, COLLECT }

    private static final int INITIAL_RADIUS = 24;
    private static final int RADIUS_STEP = 16;
    private static final double CHASE_SPEED = 1.2;
    /** Melee strike range — vanilla player entity-interaction reach ≈ 3 blocks. */
    private static final double ATTACK_REACH = 3.0;
    private static final double ATTACK_REACH_SQR = ATTACK_REACH * ATTACK_REACH;
    /** Post-hunt loot sweep: radius scanned for mob drops, and a tick budget so it can't stall. */
    private static final int COLLECT_RADIUS = 24;
    private static final int MAX_COLLECT_TICKS = 300;   // ~15 s

    private final NumenPlayer player;
    private final HuntTaskRecord r;
    private final Set<Integer> skipped = new HashSet<>();
    /** Drops A* can't reach — skipped so the sweep doesn't retry the same one forever. */
    private final Set<BlockPos> dropBlacklist = new HashSet<>();

    private Phase phase = Phase.SCAN;
    private int currentRadius;
    private int collectTicks;
    private LivingEntity target;
    private PlayerNav nav;
    private String doneReason = "done";

    public HuntCompanionTask(NumenPlayer player, HuntTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        currentRadius = Math.min(INITIAL_RADIUS, r.maxRadius);
        phase = Phase.SCAN;
    }

    @Override
    public TaskState tick() {
        if (player.isDeadOrDying()) {
            r.setState(TaskState.CANCELLED);
            return r.getState();
        }
        switch (phase) {
            case SCAN -> tickScan();
            case ENGAGE -> tickEngage();
            case COLLECT -> tickCollect();
        }
        return r.getState();
    }

    private void tickScan() {
        if (r.getKilled() >= r.count) {
            doneReason = "hunted all requested";
            beginCollect();
            return;
        }
        LivingEntity best = nearestTarget();
        if (best == null) {
            if (currentRadius < r.maxRadius) {
                currentRadius = Math.min(currentRadius + RADIUS_STEP, r.maxRadius);
                return;
            }
            if (r.getKilled() > 0) {
                doneReason = "only killed " + r.getKilled() + "/" + r.count + " within " + r.maxRadius + " blocks";
                beginCollect();   // sweep the battlefield for loot before finishing
            } else {
                doneReason = "no " + r.label + " found within " + r.maxRadius + " blocks";
                r.setState(TaskState.FAILED);
            }
            return;
        }
        target = best;
        // Arrive = in reach AND a clear line of sight, so we close around a wall rather than
        // standing behind it swinging at nothing.
        nav = new PlayerNav(player, this::targetCell, CHASE_SPEED, this::inReachAndLos);
        phase = Phase.ENGAGE;
    }

    private void tickEngage() {
        if (target == null || target.isRemoved()) {
            stopNav();
            phase = Phase.SCAN;
            return;
        }
        if (target.isDeadOrDying()) {
            r.incrementKilled();
            target = null;
            stopNav();
            phase = Phase.SCAN;
            return;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* closing distance */ }
            case ARRIVED -> swing();
            case FAILED -> {
                skipped.add(target.getId());
                target = null;
                stopNav();
                phase = Phase.SCAN;
            }
        }
    }

    /** Done fighting — switch to a post-hunt loot sweep. Mirrors auto_mine's drop collection
     *  (scan nearby item drops, walk over them so native player pickup grabs them) so a finished
     *  hunt leaves loot in the pack instead of on the ground. */
    private void beginCollect() {
        stopNav();
        collectTicks = 0;
        phase = Phase.COLLECT;
    }

    private void tickCollect() {
        if (nearbyDrops().isEmpty() || ++collectTicks > MAX_COLLECT_TICKS) {
            stopNav();
            r.setState(TaskState.SUCCESS);
            return;
        }
        if (nav == null) {
            nav = PlayerNav.toGoal(player, this::collectGoal, CHASE_SPEED, () -> nearbyDrops().isEmpty());
        }
        switch (nav.tick()) {
            case RUNNING -> { /* walking onto the next drop; native pickup collects it */ }
            case ARRIVED -> { stopNav(); r.setState(TaskState.SUCCESS); }   // nothing left in range
            case FAILED -> {                                               // nearest drop unreachable —
                blacklistNearestDrop();                                    // skip it and retry the rest
                stopNav();
            }
        }
    }

    /** Nearby dropped items to sweep up after the fight (auto_mine's droppedItemsScan, with a wider
     *  radius because mobs die spread across the engagement). Unreachable (blacklisted) ones excluded. */
    private List<BlockPos> nearbyDrops() {
        AABB box = player.getBoundingBox().inflate(COLLECT_RADIUS);
        List<BlockPos> out = new ArrayList<>();
        for (ItemEntity ie : player.level().getEntitiesOfClass(ItemEntity.class, box)) {
            if (ie.isRemoved()) continue;
            BlockPos p = ie.blockPosition();
            if (dropBlacklist.contains(p)) continue;
            out.add(p);
        }
        return out;
    }

    /** GoalComposite over every nearby drop — one A* heads for the closest reachable (auto_mine pattern). */
    private NavGoal collectGoal() {
        List<BlockPos> drops = nearbyDrops();
        if (drops.isEmpty()) return NavGoal.exact(player.blockPosition());
        List<NavGoal> goals = new ArrayList<>(drops.size());
        for (BlockPos d : drops) goals.add(NavGoal.near(d, 1.0));   // walk over it; native pickup grabs it
        return NavGoal.composite(goals);
    }

    private void blacklistNearestDrop() {
        BlockPos feet = player.blockPosition();
        nearbyDrops().stream()
                .min(java.util.Comparator.comparingDouble(feet::distSqr))
                .ifPresent(dropBlacklist::add);
    }

    /** Native melee swing: aim, fire one crosshair raytrace, and only strike when it actually
     *  resolves to THIS target (a wall / another mob in the line is not hit through), the sprint
     *  is dropped (so it sweeps and doesn't knock the mob away into another chase), and the attack
     *  cooldown has recovered (full-charge damage). */
    private void swing() {
        if (target == null) return;
        switchToBestWeapon();   // pathfinder may have swapped a scaffold block into the hand while bridging
        InputDriver.lookAt(player, target.getEyePosition());
        HitResult hit = Interaction.nativeRaytrace(player, ATTACK_REACH);
        boolean onTarget = hit.getType() == HitResult.Type.ENTITY
                && ((EntityHitResult) hit).getEntity() == target;
        if (!onTarget) {
            return;   // not actually looking at the target this tick — re-aim next tick
        }
        player.setSprinting(false);       // sweep + no knockback-chase
        if (player.getAttackStrengthScale(0.0f) >= 0.95f) {
            player.attack(target);        // real damage / cooldown / sweep / knockback / crit
            player.resetAttackStrengthTicker();
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    /** Hold the highest-attack-damage weapon from the WHOLE inventory in the main hand — the combat
     *  twin of {@code BlockDigger.switchToBestTool} (whole inventory, not just the hotbar). Mining
     *  survives the pathfinder's bridging scaffold-swap because it re-picks its tool every dig; this
     *  gives hunt the same guarantee, so a swing never lands with cobblestone instead of a sword. */
    private void switchToBestWeapon() {
        Inventory inv = player.getInventory();
        int best = inv.selected;
        double bestDmg = weaponDamage(inv.getItem(best));
        for (int i = 0; i < inv.getContainerSize(); i++) {
            double d = weaponDamage(inv.getItem(i));
            if (d > bestDmg) {
                bestDmg = d;
                best = i;
            }
        }
        player.holdInHand(best);
    }

    /** The flat main-hand attack-damage an item grants (ADD_VALUE modifiers on {@code ATTACK_DAMAGE}) —
     *  the ranking key for {@link #switchToBestWeapon}, the combat analogue of {@code getDestroySpeed}.
     *  Sword/axe carry the largest; a block or food scores 0 so it's never chosen over a real weapon. */
    private static double weaponDamage(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        // 1.20.4: attribute modifiers come from the item's per-slot Multimap, not a component.
        Multimap<Attribute, AttributeModifier> mods = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        double sum = 0.0;
        for (AttributeModifier m : mods.get(Attributes.ATTACK_DAMAGE)) {
            if (m.getOperation() == AttributeModifier.Operation.ADDITION) {
                sum += m.getAmount();
            }
        }
        return sum;
    }

    private BlockPos targetCell() {
        return (target != null && !target.isRemoved()) ? target.blockPosition() : null;
    }

    private boolean inReachAndLos() {
        return target != null
                && player.distanceToSqr(Vec3.atCenterOf(target.blockPosition())) <= ATTACK_REACH_SQR
                && player.hasLineOfSight(target);
    }

    private LivingEntity nearestTarget() {
        AABB box = player.getBoundingBox().inflate(currentRadius);
        LivingEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity e : player.level().getEntities(player, box)) {
            if (e == player || e.isRemoved()) continue;
            if (!(e instanceof LivingEntity le) || le.isDeadOrDying()) continue;
            if (!r.targets.contains(e.getType())) continue;
            if (skipped.contains(e.getId())) continue;
            double d = player.distanceToSqr(e);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = le;
            }
        }
        return best;
    }

    private void stopNav() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        stopNav();
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("killed", r.getKilled());
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(
                    "killed " + r.getKilled() + "/" + r.count + " " + r.label + " (" + doneReason + ")", data);
            case TIMEOUT -> new TaskResult(false,
                    "timed out after killing " + r.getKilled() + "/" + r.count + " " + r.label, true, false, data);
            case CANCELLED -> new TaskResult(false,
                    "interrupted after killing " + r.getKilled() + "/" + r.count + " " + r.label, false, true, data);
            case FAILED -> TaskResult.fail(doneReason, data);
            default -> TaskResult.fail("unexpected state: " + finalState, data);
        };
    }
}
