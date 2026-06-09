package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.entity.AnimusEntity;
import com.dwinovo.animus.pathing.exec.Navigator;
import com.dwinovo.animus.pathing.util.BlockHelper;
import com.dwinovo.animus.pathing.util.BlockScanner;
import com.dwinovo.animus.task.LlmTaskGoal;
import com.dwinovo.animus.task.TaskResult;
import com.dwinovo.animus.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loader for {@link LoadFurnaceTaskRecord}: validate the smelt, find or place a
 * furnace, walk to it, and load input + fuel into its real slots — then return
 * immediately. The world furnace smelts asynchronously afterwards (vanilla
 * {@code serverTick}); poll with {@code check_furnace}, empty with
 * {@code collect_furnace}.
 *
 * <h2>State machine</h2>
 * <pre>
 *   RESOLVE     → input smeltable? fuel valid? entity holds both? None → FAILED.
 *   FIND_FURNACE→ existing furnace in range → walk to it; else place one we carry;
 *                 none and none to place → FAILED.
 *   GOTO_FURNACE→ time-sliced A* to the furnace; in reach → LOAD.
 *   LOAD        → put input + computed fuel into the furnace slots → SUCCESS.
 * </pre>
 *
 * <p>Unlike {@link CraftTaskGoal} we do NOT recover a furnace we placed — it must
 * stay to smelt; the model collects from it later.
 */
public final class LoadFurnaceTaskGoal extends LlmTaskGoal<LoadFurnaceTaskRecord> {

    private enum Phase { RESOLVE, FIND_FURNACE, GOTO_FURNACE, LOAD }

    private static final double WALK_SPEED = 1.0;
    private static final int SLOT_MAX = 64;

    private Phase phase = Phase.RESOLVE;
    private SmeltingRecipe recipe;
    private int cookTime = FurnaceEngine.STANDARD_COOK_TIME;
    private String outputLabel = "?";

    private BlockPos furnacePos;
    private Navigator nav;

    private String doneReason = "done";
    private final Map<String, Object> resultData = new HashMap<>();

    public LoadFurnaceTaskGoal(AnimusEntity entity) {
        super(entity, LoadFurnaceTaskRecord.TOOL_NAME, LoadFurnaceTaskRecord.class);
    }

    @Override
    protected void onStart(LoadFurnaceTaskRecord r) {
        this.phase = Phase.RESOLVE;
        this.recipe = null;
        this.furnacePos = null;
        this.nav = null;
        this.resultData.clear();
    }

    @Override
    protected void onTick(LoadFurnaceTaskRecord r) {
        switch (phase) {
            case RESOLVE -> tickResolve(r);
            case FIND_FURNACE -> tickFindFurnace(r);
            case GOTO_FURNACE -> tickGotoFurnace(r);
            case LOAD -> tickLoad(r);
        }
    }

    // ---- RESOLVE: validate the smelt is possible with what we hold ----

    private void tickResolve(LoadFurnaceTaskRecord r) {
        if (!(entity.level() instanceof ServerLevel sl)) {
            fail("not on a server level");
            return;
        }
        SimpleContainer inv = entity.getInventory();
        if (inv.countItem(r.input) <= 0) {
            fail("no " + r.label + " in inventory to smelt");
            return;
        }
        recipe = FurnaceEngine.smeltingRecipe(sl, new ItemStack(r.input));
        if (recipe == null) {
            fail(r.label + " can't be smelted in a furnace (no smelting recipe)");
            return;
        }
        cookTime = recipe.cookingTime() > 0 ? recipe.cookingTime() : FurnaceEngine.STANDARD_COOK_TIME;
        outputLabel = BuiltInRegistries.ITEM.getKey(
                FurnaceEngine.smeltResult(sl, recipe, new ItemStack(r.input)).getItem()).toString();

        if (!FurnaceEngine.isFuel(sl, new ItemStack(r.fuel))) {
            fail(BuiltInRegistries.ITEM.getKey(r.fuel) + " is not valid furnace fuel — "
                    + "use coal/charcoal, a log/plank, etc.");
            return;
        }
        if (inv.countItem(r.fuel) <= 0) {
            fail("no " + BuiltInRegistries.ITEM.getKey(r.fuel).getPath() + " in inventory to use as fuel");
            return;
        }
        phase = Phase.FIND_FURNACE;
    }

    // ---- FIND_FURNACE: locate, walk to, or place a furnace ----

    private void tickFindFurnace(LoadFurnaceTaskRecord r) {
        Level level = entity.level();
        List<BlockScanner.Hit> furnaces = BlockScanner.findWithin(
                level, entity.blockPosition(), r.searchRadius, Set.of(Blocks.FURNACE));

        if (!furnaces.isEmpty()) {
            furnacePos = furnaces.get(0).pos();
            if (withinReach(furnacePos)) {
                phase = Phase.LOAD;
            } else {
                nav = new Navigator(entity, furnacePos, WALK_SPEED, () -> withinReach(furnacePos));
                phase = Phase.GOTO_FURNACE;
            }
            return;
        }

        BlockPos spot = placeFurnaceBeside();
        if (spot != null) {
            furnacePos = spot;
            phase = Phase.LOAD;            // placed adjacent → already in reach
        } else {
            fail("no furnace nearby and none in inventory to place — craft/obtain a furnace first");
        }
    }

    // ---- GOTO_FURNACE: drive the pathfinder to the furnace ----

    private void tickGotoFurnace(LoadFurnaceTaskRecord r) {
        switch (nav.tick()) {
            case RUNNING -> { /* keep walking; planned while moving */ }
            case ARRIVED -> { nav.stop(); phase = Phase.LOAD; }
            case FAILED -> {
                if (withinReach(furnacePos)) {
                    nav.stop();
                    phase = Phase.LOAD;
                } else {
                    fail("can't reach the furnace");
                }
            }
        }
    }

    // ---- LOAD: put input + fuel into the real furnace slots ----

    private void tickLoad(LoadFurnaceTaskRecord r) {
        Level level = entity.level();
        if (level.getBlockState(furnacePos).getBlock() != Blocks.FURNACE) {
            // Walked-to furnace vanished; re-find.
            phase = Phase.FIND_FURNACE;
            return;
        }
        AbstractFurnaceBlockEntity furnace = FurnaceEngine.furnaceAt(level, furnacePos);
        if (furnace == null) {
            fail("furnace at target has no block entity");
            return;
        }
        SimpleContainer inv = entity.getInventory();

        // Input slot.
        ItemStack curInput = furnace.getItem(FurnaceEngine.SLOT_INPUT);
        if (!curInput.isEmpty() && curInput.getItem() != r.input) {
            fail("furnace input slot is busy with "
                    + BuiltInRegistries.ITEM.getKey(curInput.getItem()).getPath());
            return;
        }
        int inputRoom = SLOT_MAX - (curInput.isEmpty() ? 0 : curInput.getCount());
        int loadInput = Math.min(Math.min(r.count, inputRoom), inv.countItem(r.input));
        if (loadInput <= 0) {
            fail("nothing to load — input slot full or no " + r.label + " left");
            return;
        }

        // Fuel slot — compute how much is needed for the input we're loading.
        ItemStack curFuel = furnace.getItem(FurnaceEngine.SLOT_FUEL);
        if (!curFuel.isEmpty() && curFuel.getItem() != r.fuel) {
            fail("furnace fuel slot is busy with "
                    + BuiltInRegistries.ITEM.getKey(curFuel.getItem()).getPath());
            return;
        }
        int fuelNeeded = FurnaceEngine.fuelItemsNeeded(level, new ItemStack(r.fuel), loadInput, cookTime);
        int fuelRoom = SLOT_MAX - (curFuel.isEmpty() ? 0 : curFuel.getCount());
        int loadFuel = Math.min(Math.min(fuelNeeded, fuelRoom), inv.countItem(r.fuel));
        if (loadFuel <= 0) {
            fail("no fuel to load (need ~" + fuelNeeded + " "
                    + BuiltInRegistries.ITEM.getKey(r.fuel).getPath() + ")");
            return;
        }

        // Commit: pull from inventory, write to furnace slots.
        inv.removeItemType(r.input, loadInput);
        inv.removeItemType(r.fuel, loadFuel);
        furnace.setItem(FurnaceEngine.SLOT_INPUT,
                new ItemStack(r.input, (curInput.isEmpty() ? 0 : curInput.getCount()) + loadInput));
        furnace.setItem(FurnaceEngine.SLOT_FUEL,
                new ItemStack(r.fuel, (curFuel.isEmpty() ? 0 : curFuel.getCount()) + loadFuel));
        furnace.setChanged();
        inv.setChanged();

        resultData.put("x", furnacePos.getX());
        resultData.put("y", furnacePos.getY());
        resultData.put("z", furnacePos.getZ());
        resultData.put("loaded_input", loadInput);
        resultData.put("loaded_fuel", loadFuel);
        resultData.put("output_item", outputLabel);
        resultData.put("eta_seconds_approx", loadInput * cookTime / 20);

        String fuelWarn = loadFuel < fuelNeeded
                ? " (fuel only covers ~" + (loadFuel * level.fuelValues().burnDuration(new ItemStack(r.fuel)) / cookTime)
                        + " items — add more fuel or run again)"
                : "";
        doneReason = "loaded " + loadInput + "x " + r.label + " + " + loadFuel + "x "
                + BuiltInRegistries.ITEM.getKey(r.fuel).getPath() + " into furnace at "
                + furnacePos.getX() + "," + furnacePos.getY() + "," + furnacePos.getZ()
                + "; smelting into " + outputLabel + ", ~" + (loadInput * cookTime / 20)
                + "s — collect_furnace there when done" + fuelWarn;
        currentRecord.setState(TaskState.SUCCESS);
    }

    // ---- helpers (mirror CraftTaskGoal) ----

    private boolean withinReach(BlockPos pos) {
        return entity.onGround()
                && entity.distanceToSqr(Vec3.atCenterOf(pos)) <= BlockMiningProgress.REACH_SQR;
    }

    private BlockPos placeFurnaceBeside() {
        if (entity.getInventory().countItem(Items.FURNACE) <= 0) {
            return null;
        }
        Level level = entity.level();
        BlockPos feet = entity.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos at = feet.relative(dir);
            if (BlockHelper.isReplaceableForPlacement(level, at)
                    && BlockHelper.canWalkOn(level, at.below())) {
                if (!level.setBlock(at, Blocks.FURNACE.defaultBlockState(), 3)) {
                    continue;
                }
                entity.getInventory().removeItemType(Items.FURNACE, 1);
                entity.getInventory().setChanged();
                playPlaceSound(at);
                return at;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void playPlaceSound(BlockPos at) {
        SoundType sound = Blocks.FURNACE.defaultBlockState().getSoundType();
        entity.level().playSound(null, at, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
    }

    private void fail(String reason) {
        doneReason = reason;
        currentRecord.setState(TaskState.FAILED);
    }

    @Override
    protected TaskResult buildResult(LoadFurnaceTaskRecord r, TaskState finalState) {
        if (nav != null) nav.stop();
        entity.getNavigation().stop();
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(doneReason, resultData);
            case TIMEOUT -> TaskResult.timeout("timed out before loading the furnace: " + doneReason);
            case CANCELLED -> TaskResult.cancelled("load_furnace interrupted");
            case FAILED -> TaskResult.fail(doneReason);
            default -> TaskResult.fail("unexpected state: " + finalState);
        };
    }
}
